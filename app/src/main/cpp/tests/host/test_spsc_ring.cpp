#include "audio/SpscAudioRing.h"
#include "tests/host/test_main.h"
#include <atomic>
#include <algorithm>
#include <thread>

using temposcore::SpscAudioRing;
using temposcore::AudioSourceSpan;

void test_fifo() {
    SpscAudioRing ring(128); float in[100], out[100];
    for (int i = 0; i < 100; ++i) in[i] = static_cast<float>(i);
    CHECK(ring.write(in, 100) == 100); CHECK(ring.read(out, 100) == 100);
    for (int i = 0; i < 100; ++i) CHECK_NEAR(out[i], in[i], 0.0f);
}
void test_wrap() {
    SpscAudioRing ring(8); float in[5], out[5];
    for (int round = 0; round < 5; ++round) { for (int i=0;i<5;++i) in[i]=round*5+i;
        CHECK(ring.write(in,5)==5); CHECK(ring.read(out,5)==5);
        for(int i=0;i<5;++i) CHECK_NEAR(out[i],in[i],0.0f); }
}
void test_overflow() { SpscAudioRing ring(8); float in[16], out[8]; for(int i=0;i<16;++i)in[i]=i;
    CHECK(ring.write(in,16)==8); CHECK(ring.droppedSamples()==8); CHECK(ring.read(out,8)==8);
    for(int i=0;i<8;++i)CHECK_NEAR(out[i],i,0.0f); }
void test_empty_and_full() { SpscAudioRing ring(4); float x[5]={0,1,2,3,4}, out[4];
    CHECK(ring.read(out,1)==0); CHECK(ring.write(x,4)==4); CHECK(ring.write(x+4,1)==0); CHECK(ring.droppedSamples()==1); }
void test_available_snapshot_bounds() {
    CHECK(SpscAudioRing::boundedAvailableSnapshot(100, 105, 8) == 5);
    // Consumer advances and producer refills between observer loads. Stale tail
    // can overstate occupancy, but diagnostic availability must stay physical.
    CHECK(SpscAudioRing::boundedAvailableSnapshot(100, 120, 8) == 8);
    CHECK(SpscAudioRing::boundedAvailableSnapshot(120, 100, 8) == 0);
    CHECK(SpscAudioRing::boundedAvailableSnapshot(0, UINT64_MAX, 8) == 8);
}
void test_stamped_reads_stop_at_drop_discontinuity() {
    SpscAudioRing ring(8);
    float input[8] = {0, 1, 2, 3, 4, 5, 6, 7};
    float output[8]{};
    AudioSourceSpan span{7, 3, 11, 100, 6};
    CHECK(ring.writeStamped(input, 6, span) == 6);
    span.firstFrame = 106;
    span.frameCount = 6;
    CHECK(ring.writeStamped(input + 2, 6, span) == 2); // suffix drops; source still advances by six.

    AudioSourceSpan readSpan;
    CHECK(ring.peekNextSourceSpan(readSpan));
    CHECK(readSpan.firstFrame == 100 && readSpan.frameCount == 6);
    CHECK(ring.readStamped(output, 8, readSpan) == 6);
    CHECK(readSpan.streamEpoch == 7 && readSpan.continuityEpoch == 3);
    CHECK(readSpan.positionGeneration == 11 && readSpan.firstFrame == 100 && readSpan.frameCount == 6);
    CHECK(ring.readStamped(output, 8, readSpan) == 2);
    CHECK(readSpan.firstFrame == 106 && readSpan.frameCount == 2 && readSpan.continuityEpoch == 3);
    CHECK(ring.droppedSamples() == 4);

    span.firstFrame = 112;
    span.frameCount = 4;
    span.continuityEpoch = 4;
    CHECK(ring.writeStamped(input, 4, span) == 4);
    CHECK(ring.peekNextSourceSpan(readSpan));
    CHECK(readSpan.firstFrame == 112 && readSpan.frameCount == 4 && readSpan.continuityEpoch == 4);
    CHECK(ring.readStamped(output, 8, readSpan) == 4);
    CHECK(readSpan.firstFrame == 112 && readSpan.continuityEpoch == 4);
}
void test_concurrent() {
    SpscAudioRing ring(1024); std::atomic<bool> done{false}; std::atomic<bool> availabilityBounded{true};
    std::atomic<size_t> maximumAvailable{0}; size_t readerCount = 0; bool ordered = true;
    std::thread reader([&] {
        float out[256];
        float prev = -1.0f;
        while (!done.load(std::memory_order_acquire) || ring.available() != 0) {
            const size_t count = ring.read(out, 256);
            for (size_t i = 0; i < count; ++i) {
                // Drop-incoming can drop mid-sequence samples, so the reader sees a
                // subsequence, not necessarily a contiguous prefix. The invariant is
                // strictly-increasing order, which drops preserve.
                if (out[i] <= prev) ordered = false;
                prev = out[i];
                ++readerCount;
            }
            if (!count) std::this_thread::yield();
        }
    });
    std::thread observer([&] {
        while (!done.load(std::memory_order_acquire) || ring.available() != 0) {
            const size_t available = ring.available();
            if (available > ring.capacity()) availabilityBounded.store(false, std::memory_order_relaxed);
            size_t previous = maximumAvailable.load(std::memory_order_relaxed);
            while (available > previous &&
                   !maximumAvailable.compare_exchange_weak(previous, available, std::memory_order_relaxed)) {}
        }
    });
    float in[256];
    for (size_t base = 0; base < 10000; base += 256) {
        const size_t count = std::min<size_t>(256, 10000 - base);
        for (size_t i = 0; i < count; ++i) in[i] = static_cast<float>(base + i);
        ring.write(in, count);
    }
    done.store(true, std::memory_order_release); reader.join(); observer.join();
    CHECK(readerCount + ring.droppedSamples() == 10000); CHECK(ordered);
    CHECK(availabilityBounded.load(std::memory_order_relaxed));
    CHECK(maximumAvailable.load(std::memory_order_relaxed) <= ring.capacity());
}
REGISTER_TEST(test_fifo); REGISTER_TEST(test_wrap); REGISTER_TEST(test_overflow); REGISTER_TEST(test_empty_and_full);
REGISTER_TEST(test_available_snapshot_bounds); REGISTER_TEST(test_stamped_reads_stop_at_drop_discontinuity);
REGISTER_TEST(test_concurrent);
