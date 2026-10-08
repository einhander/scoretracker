#pragma once

#include "audio/AudioTimeline.h"

#include <atomic>
#include <cstdint>
#include <memory>

namespace temposcore {

struct TransportHistorySegment {
    uint64_t streamEpoch = 0;
    uint64_t continuityEpoch = 0;
    uint64_t positionGeneration = 0;
    uint64_t sourceStartFrame = 0;
    uint32_t frameCount = 0;
    double positionStart = 0.0;
    double baseBeatsStart = 0.0;
    double baseDeltaBeats = 0.0;
    double correctionDeltaBeats = 0.0;
    double startRelocationBeats = 0.0;

    uint64_t sourceEndFrame() const noexcept { return sourceStartFrame + frameCount; }
};

struct TransportHistoryPoint {
    double position = 0.0;
    double baseBeats = 0.0;
};

struct TransportDiagnosticRecord {
    uint64_t streamEpoch = 0;
    uint64_t continuityEpoch = 0;
    uint64_t positionGeneration = 0;
    uint64_t sourceEndFrame = 0;
    uint64_t observationSequence = 0;
    uint64_t observationAgeFrames = 0;
    double baseDeltaBeats = 0.0;
    double correctionDeltaBeats = 0.0;
    double actualDeltaBeats = 0.0;
    double relocationDeltaBeats = 0.0;
    double rawObservationError = 0.0;
    double projectedCorrectionError = 0.0;
    uint32_t rejectionCode = 0;
};

// Single-thread-owned, fixed-size timeline. RT instance is only read/written by
// callback; analyzer owns a separate copy populated through the SPSC channel.
class TransportHistory final {
public:
    static constexpr uint32_t Capacity = 32768;

    TransportHistory() : segments_(new TransportHistorySegment[Capacity]) {}

    void clear() noexcept { writeSequence_ = 0; }
    void append(const TransportHistorySegment& segment) noexcept {
        segments_[writeSequence_ % Capacity] = segment;
        ++writeSequence_;
    }

    bool lookup(const AudioSourceSpan& stamp, TransportHistoryPoint& point) const noexcept {
        if (!writeSequence_ || stamp.frameCount != 0) return false;
        const uint64_t oldest = writeSequence_ > Capacity ? writeSequence_ - Capacity : 0;
        uint64_t low = oldest;
        uint64_t high = writeSequence_;
        // Source frames are strictly increasing within a stream epoch. Select
        // newest segment whose start is <= requested frame (important at gaps).
        while (low < high) {
            const uint64_t mid = low + (high - low) / 2;
            if (segments_[mid % Capacity].sourceStartFrame <= stamp.firstFrame) low = mid + 1;
            else high = mid;
        }
        if (low == oldest) return false;
        const auto& segment = segments_[(low - 1) % Capacity];
        if (segment.streamEpoch != stamp.streamEpoch ||
            segment.continuityEpoch != stamp.continuityEpoch ||
            segment.positionGeneration != stamp.positionGeneration ||
            stamp.firstFrame > segment.sourceEndFrame() || segment.frameCount == 0) return false;

        const uint64_t offset = stamp.firstFrame - segment.sourceStartFrame;
        const double fraction = static_cast<double>(offset) / segment.frameCount;
        point.baseBeats = segment.baseBeatsStart + segment.baseDeltaBeats * fraction;
        point.position = segment.positionStart +
            (segment.baseDeltaBeats + segment.correctionDeltaBeats) * fraction;
        return true;
    }

private:
    std::unique_ptr<TransportHistorySegment[]> segments_;
    uint64_t writeSequence_ = 0;
};

// Nonblocking, bounded RT-producer -> analyzer-consumer history transfer.
// Slots are plain payloads protected by release/acquire ownership indices; no
// seqlock/plain-payload concurrent reads and no slot overwrite on overflow.
class TransportHistoryChannel final {
public:
    static constexpr uint32_t Capacity = TransportHistory::Capacity;
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "Transport history indices must be lock-free on supported ABIs");

    TransportHistoryChannel() : segments_(new TransportHistorySegment[Capacity]) {}

    bool push(const TransportHistorySegment& segment) noexcept {
        const uint32_t head = head_.load(std::memory_order_relaxed);
        const uint32_t tail = tail_.load(std::memory_order_acquire);
        if (head - tail >= Capacity) {
            overflowCount_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        segments_[head % Capacity] = segment;
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    bool pop(TransportHistorySegment& segment) noexcept {
        const uint32_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        segment = segments_[tail % Capacity];
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    uint32_t overflowCount() const noexcept { return overflowCount_.load(std::memory_order_relaxed); }
    void clearStopped() noexcept {
        tail_.store(head_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        overflowCount_.store(0, std::memory_order_relaxed);
    }

private:
    std::unique_ptr<TransportHistorySegment[]> segments_;
    std::atomic<uint32_t> head_{0};
    std::atomic<uint32_t> tail_{0};
    std::atomic<uint32_t> overflowCount_{0};
};

class TransportDiagnosticsChannel final {
public:
    static constexpr uint32_t Capacity = 2048;
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "Transport diagnostics indices must be lock-free on supported ABIs");

    TransportDiagnosticsChannel() : records_(new TransportDiagnosticRecord[Capacity]) {}
    bool push(const TransportDiagnosticRecord& record) noexcept {
        const uint32_t head = head_.load(std::memory_order_relaxed);
        const uint32_t tail = tail_.load(std::memory_order_acquire);
        if (head - tail >= Capacity) {
            dropped_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        records_[head % Capacity] = record;
        head_.store(head + 1, std::memory_order_release);
        return true;
    }
    bool pop(TransportDiagnosticRecord& record) noexcept {
        const uint32_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        record = records_[tail % Capacity];
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }
    uint32_t dropped() const noexcept { return dropped_.load(std::memory_order_relaxed); }
    void clearStopped() noexcept {
        tail_.store(head_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        dropped_.store(0, std::memory_order_relaxed);
    }

private:
    std::unique_ptr<TransportDiagnosticRecord[]> records_;
    std::atomic<uint32_t> head_{0};
    std::atomic<uint32_t> tail_{0};
    std::atomic<uint32_t> dropped_{0};
};

} // namespace temposcore
