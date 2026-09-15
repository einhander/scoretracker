#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace temposcore {

// Drop incoming samples on overflow. Head publishes storage with release/acquire;
// producer never changes consumer-owned tail.
class SpscAudioRing final {
public:
    explicit SpscAudioRing(size_t capacity);
    ~SpscAudioRing() = default;
    SpscAudioRing(const SpscAudioRing&) = delete;
    SpscAudioRing& operator=(const SpscAudioRing&) = delete;

    size_t write(const float* data, size_t n) noexcept;
    size_t read(float* out, size_t n) noexcept;
    size_t available() const noexcept;
    size_t capacity() const noexcept { return capacity_; }
    uint64_t droppedSamples() const noexcept { return droppedSamples_.load(std::memory_order_relaxed); }
    void reset() noexcept;

private:
    std::unique_ptr<float[]> storage_;
    const size_t capacity_;
    std::atomic<uint64_t> head_{0};
    std::atomic<uint64_t> tail_{0};
    std::atomic<uint64_t> droppedSamples_{0};
};

} // namespace temposcore
