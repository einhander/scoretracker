#include "audio/SpscAudioRing.h"

#include <algorithm>

namespace temposcore {

SpscAudioRing::SpscAudioRing(size_t capacity)
    : storage_(new float[capacity]), descriptors_(new Descriptor[capacity]), capacity_(capacity) {}

size_t SpscAudioRing::write(const float* data, size_t n) noexcept {
    AudioSourceSpan span{1, 1, 0, legacyWriteFrame_, static_cast<uint32_t>(n)};
    legacyWriteFrame_ += n;
    return writeStamped(data, n, span);
}

size_t SpscAudioRing::writeStamped(const float* data, size_t n, const AudioSourceSpan& span) noexcept {
    if (!n || !data) return 0;
    if (n > UINT32_MAX || span.frameCount != n) {
        droppedSamples_.fetch_add(n, std::memory_order_relaxed);
        return 0;
    }
    const uint64_t head = head_.load(std::memory_order_relaxed);
    const uint64_t tail = tail_.load(std::memory_order_acquire);
    const uint64_t used = head - tail;
    const uint64_t capacity = static_cast<uint64_t>(capacity_);
    const size_t writable = static_cast<size_t>(std::min<uint64_t>(n, capacity - std::min(used, capacity)));
    if (!writable) {
        droppedSamples_.fetch_add(n, std::memory_order_relaxed);
        return 0;
    }
    const uint32_t descriptorHead = descriptorHead_.load(std::memory_order_relaxed);
    const uint32_t descriptorTail = descriptorTail_.load(std::memory_order_acquire);
    if (descriptorHead - descriptorTail >= capacity_) {
        droppedSamples_.fetch_add(n, std::memory_order_relaxed);
        return 0;
    }
    for (size_t i = 0; i < writable; ++i) storage_[(head + i) % capacity_] = data[i];
    Descriptor& descriptor = descriptors_[descriptorHead % capacity_];
    descriptor.streamEpoch = span.streamEpoch;
    descriptor.continuityEpoch = span.continuityEpoch;
    descriptor.positionGeneration = span.positionGeneration;
    descriptor.firstFrame = span.firstFrame;
    descriptor.frameCount = static_cast<uint32_t>(writable);
    head_.store(head + writable, std::memory_order_release);
    descriptorHead_.store(descriptorHead + 1, std::memory_order_release);
    droppedSamples_.fetch_add(n - writable, std::memory_order_relaxed);
    return writable;
}

size_t SpscAudioRing::read(float* out, size_t n) noexcept {
    AudioSourceSpan ignored;
    return readStamped(out, n, ignored);
}

size_t SpscAudioRing::readStamped(float* out, size_t n, AudioSourceSpan& span) noexcept {
    if (!n || !out) return 0;
    const uint32_t descriptorTail = descriptorTail_.load(std::memory_order_relaxed);
    const uint32_t descriptorHead = descriptorHead_.load(std::memory_order_acquire);
    if (descriptorTail == descriptorHead) return 0;
    const Descriptor& descriptor = descriptors_[descriptorTail % capacity_];
    const uint64_t head = head_.load(std::memory_order_acquire);
    const uint64_t tail = tail_.load(std::memory_order_relaxed);
    const size_t remaining = descriptor.frameCount - descriptorReadOffset_;
    const size_t count = static_cast<size_t>(std::min<uint64_t>({n, remaining, head - tail}));
    if (!count) return 0;
    for (size_t i = 0; i < count; ++i) out[i] = storage_[(tail + i) % capacity_];
    span.streamEpoch = descriptor.streamEpoch;
    span.continuityEpoch = descriptor.continuityEpoch;
    span.positionGeneration = descriptor.positionGeneration;
    span.firstFrame = descriptor.firstFrame + descriptorReadOffset_;
    span.frameCount = static_cast<uint32_t>(count);
    tail_.store(tail + count, std::memory_order_release);
    descriptorReadOffset_ += count;
    if (descriptorReadOffset_ == descriptor.frameCount) {
        descriptorReadOffset_ = 0;
        descriptorTail_.store(descriptorTail + 1, std::memory_order_release);
    }
    return count;
}

bool SpscAudioRing::peekNextSourceSpan(AudioSourceSpan& span) const noexcept {
    const uint32_t descriptorTail = descriptorTail_.load(std::memory_order_relaxed);
    if (descriptorTail == descriptorHead_.load(std::memory_order_acquire)) return false;
    const Descriptor& descriptor = descriptors_[descriptorTail % capacity_];
    span.streamEpoch = descriptor.streamEpoch;
    span.continuityEpoch = descriptor.continuityEpoch;
    span.positionGeneration = descriptor.positionGeneration;
    span.firstFrame = descriptor.firstFrame + descriptorReadOffset_;
    span.frameCount = descriptor.frameCount - static_cast<uint32_t>(descriptorReadOffset_);
    return true;
}

size_t SpscAudioRing::available() const noexcept {
    // Consumer owns tail, producer owns head. Read tail first so the subsequent
    // head observation cannot precede the consumer's tail publication; a racing
    // producer can still make this stale-tail snapshot overstate occupancy.
    const uint64_t tail = tail_.load(std::memory_order_acquire);
    const uint64_t head = head_.load(std::memory_order_acquire);
    return boundedAvailableSnapshot(tail, head, capacity_);
}

size_t SpscAudioRing::boundedAvailableSnapshot(uint64_t observedTail, uint64_t observedHead,
                                               size_t capacity) noexcept {
    if (observedHead <= observedTail) return 0;
    return static_cast<size_t>(std::min<uint64_t>(observedHead - observedTail, capacity));
}

void SpscAudioRing::reset() noexcept {
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
    descriptorHead_.store(0, std::memory_order_relaxed);
    descriptorTail_.store(0, std::memory_order_relaxed);
    descriptorReadOffset_ = 0;
    legacyWriteFrame_ = 0;
    droppedSamples_.store(0, std::memory_order_relaxed);
}

} // namespace temposcore
