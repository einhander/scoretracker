#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include "audio/AudioTimeline.h"

namespace temposcore {

static_assert(std::atomic<uint64_t>::is_always_lock_free,
              "SPSC cursor atomics must be lock-free on supported audio ABIs");

// Drop incoming samples on overflow. Head publishes storage with release/acquire;
// producer never changes consumer-owned tail.
class SpscAudioRing final {
public:
    explicit SpscAudioRing(size_t capacity);
    ~SpscAudioRing() = default;
    SpscAudioRing(const SpscAudioRing&) = delete;
    SpscAudioRing& operator=(const SpscAudioRing&) = delete;

    // Unstamped legacy FIFO shim for host tests only; production capture uses writeStamped.
    size_t write(const float* data, size_t n) noexcept;
    size_t writeStamped(const float* data, size_t n, const AudioSourceSpan& span) noexcept;
    // Unstamped legacy FIFO shim for host tests only; production analyzer uses readStamped.
    size_t read(float* out, size_t n) noexcept;
    size_t readStamped(float* out, size_t n, AudioSourceSpan& span) noexcept;
    // Consumer-thread-only metadata peek; does not copy PCM or move ownership.
    bool peekNextSourceSpan(AudioSourceSpan& span) const noexcept;
    size_t available() const noexcept;
    uint64_t writtenSamples() const noexcept { return head_.load(std::memory_order_relaxed); }
    uint64_t consumedSamples() const noexcept { return tail_.load(std::memory_order_relaxed); }
    // Clamp an observational (possibly stale-tail/new-head) snapshot to the
    // ring's physical capacity; exposed for deterministic invariant tests.
    static size_t boundedAvailableSnapshot(uint64_t observedTail, uint64_t observedHead,
                                           size_t capacity) noexcept;
    size_t capacity() const noexcept { return capacity_; }
    uint64_t droppedSamples() const noexcept { return droppedSamples_.load(std::memory_order_relaxed); }
    void reset() noexcept;

private:
    struct Descriptor {
        uint64_t streamEpoch = 0;
        uint64_t continuityEpoch = 0;
        uint64_t positionGeneration = 0;
        uint64_t firstFrame = 0;
        uint32_t frameCount = 0;
    };
    std::unique_ptr<float[]> storage_;
    std::unique_ptr<Descriptor[]> descriptors_;
    const size_t capacity_;
    std::atomic<uint64_t> head_{0};
    std::atomic<uint64_t> tail_{0};
    std::atomic<uint32_t> descriptorHead_{0};
    std::atomic<uint32_t> descriptorTail_{0};
    size_t descriptorReadOffset_ = 0;
    uint64_t legacyWriteFrame_ = 0;
    std::atomic<uint64_t> droppedSamples_{0};
};

} // namespace temposcore
