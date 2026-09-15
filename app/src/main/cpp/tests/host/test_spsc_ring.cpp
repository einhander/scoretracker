#include "audio/SpscAudioRing.h"
#include "tests/host/test_main.h"
#include <atomic>
#include <algorithm>
#include <thread>

using temposcore::SpscAudioRing;

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
void test_concurrent() {
    SpscAudioRing ring(1024); std::atomic<bool> done{false}; size_t readerCount = 0; bool ordered = true;
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
    float in[256];
    for (size_t base = 0; base < 10000; base += 256) {
        const size_t count = std::min<size_t>(256, 10000 - base);
        for (size_t i = 0; i < count; ++i) in[i] = static_cast<float>(base + i);
        ring.write(in, count);
    }
    done.store(true, std::memory_order_release); reader.join();
    CHECK(readerCount + ring.droppedSamples() == 10000); CHECK(ordered);
}
REGISTER_TEST(test_fifo); REGISTER_TEST(test_wrap); REGISTER_TEST(test_overflow); REGISTER_TEST(test_empty_and_full); REGISTER_TEST(test_concurrent);
