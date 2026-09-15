#include "audio/SpscAudioRing.h"

#include <algorithm>

namespace temposcore {

SpscAudioRing::SpscAudioRing(size_t capacity)
    : storage_(new float[capacity]), capacity_(capacity) {}

size_t SpscAudioRing::write(const float* data, size_t n) noexcept {
    const uint64_t head = head_.load(std::memory_order_relaxed);
    const uint64_t tail = tail_.load(std::memory_order_acquire);
    const uint64_t used = head - tail;
    const uint64_t capacity = static_cast<uint64_t>(capacity_);
    const size_t writable = static_cast<size_t>(std::min<uint64_t>(n, capacity - std::min(used, capacity)));
    for (size_t i = 0; i < writable; ++i) storage_[(head + i) % capacity_] = data[i];
    head_.store(head + writable, std::memory_order_release);
    droppedSamples_.fetch_add(n - writable, std::memory_order_relaxed);
    return writable;
}

size_t SpscAudioRing::read(float* out, size_t n) noexcept {
    const uint64_t head = head_.load(std::memory_order_acquire);
    const uint64_t tail = tail_.load(std::memory_order_relaxed);
    const size_t count = static_cast<size_t>(std::min<uint64_t>(n, head - tail));
    for (size_t i = 0; i < count; ++i) out[i] = storage_[(tail + i) % capacity_];
    tail_.store(tail + count, std::memory_order_release);
    return count;
}

size_t SpscAudioRing::available() const noexcept {
    return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
}

void SpscAudioRing::reset() noexcept {
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
    droppedSamples_.store(0, std::memory_order_relaxed);
}

} // namespace temposcore
