#pragma once

#include "beat/BeatTracker.h"
#include "position/DtwMatcher.h"
#include "transport/TransportHistory.h"

#include <array>
#include <atomic>
#include <cstdint>
#include <memory>

namespace temposcore {

static_assert(std::atomic<uint64_t>::is_always_lock_free && std::atomic<double>::is_always_lock_free,
              "Existing callback transport atomics must be lock-free on supported audio ABIs");

struct TransportState {
    // Beat/transport loop (fast).
    double transportBpm = 120.0;
    double detectedBpm = 0.0;
    double quarterBeatPosition = 0.0;
    double confidence = 0.0; // beat confidence
    double rms = 0.0;
    bool running = false;
    // Score-following loop (slow) — spec §28 fields 6-11.
    double positionConfidence = 0.0;
    double matchedQuarterBeatPosition = 0.0;
    double positionErrorBeats = 0.0;
    int positionStateCode = 0; // PositionTrackingState as int (0..4)
    double ambiguityMargin = 0.0;
    double validContextSeconds = 0.0;
};

class LiveTransport {
public:
    LiveTransport();
    LiveTransport(const LiveTransport&) = delete;
    LiveTransport& operator=(const LiveTransport&) = delete;
    // Called with callback/analyzer stopped. New source epoch; score position is
    // retained, but prior PCM history/mailboxes become unqueryable.
    void beginStreamEpoch(uint64_t streamEpoch) noexcept;
    void configure(double expectedBpm, double startQuarterBeat) noexcept;
    void resetPosition(double startQuarterBeat) noexcept;
    void setExpectedBpm(double expectedBpm) noexcept;
    void setRunning(bool running) noexcept;
    // Publish an Idle score-following state (main thread, on stop) so the UI
    // does not keep showing a stale LOCKED/Weak state after Stop (spec §30).
    void publishIdle() noexcept;
    void invalidatePositionState() noexcept;
    // Publish the latest score-following observation (analyzer thread). The
    // validContextSeconds is the number of valid seconds in the live feature
    // window (spec §28 field 11); the analyzer owns the FeatureRing.
    void submitPositionObservation(const PositionObservation&, double validContextSeconds) noexcept;
    void submitTempoObservation(const BeatObservation& observation) noexcept;
    // Unstamped compatibility for legacy host transport tests only. Production
    // microphone/test PCM must call processSourceFrames with capture stamps.
    void processFrames(int32_t numFrames, int32_t sampleRate) noexcept;
    uint64_t processSourceFrames(int32_t numFrames, int32_t sampleRate,
                                 uint64_t streamEpoch, uint64_t continuityEpoch,
                                 uint64_t firstSourceFrame) noexcept;
    bool popHistorySegment(TransportHistorySegment& segment) noexcept { return historyChannel_->pop(segment); }
    uint32_t historyOverflowCount() const noexcept { return historyChannel_->overflowCount(); }
    // Monotonic across stream epochs; published by source producer before any
    // PCM ring drop and independent of worker history draining.
    uint64_t capturedFrameWatermark() const noexcept {
        return capturedFrameWatermark_.load(std::memory_order_acquire);
    }
    uint64_t streamEpoch() const noexcept { return publishedStreamEpoch_.load(std::memory_order_acquire); }
    uint32_t droppedObservations() const noexcept { return droppedObservations_.load(std::memory_order_relaxed); }
    void enableDiagnostics(bool enabled) noexcept { diagnosticsEnabled_.store(enabled, std::memory_order_relaxed); }
    bool popDiagnostic(TransportDiagnosticRecord& record) noexcept { return diagnosticChannel_->pop(record); }
    uint32_t droppedDiagnosticRecords() const noexcept { return diagnosticChannel_->dropped(); }
    TransportState snapshot() const noexcept;
    uint64_t positionGeneration() const noexcept {
        return positionGeneration_.load(std::memory_order_acquire);
    }

private:
    struct PendingObservation {
        PositionObservation observation{};
        double validContextSeconds = 0.0;
    };
    static constexpr uint32_t ObservationCapacity = 8;
    static_assert(std::atomic<uint32_t>::is_always_lock_free,
                  "Observation mailbox indices must be lock-free on supported ABIs");
    bool enqueueObservation(const PendingObservation& observation) noexcept;
    bool dequeueObservation(PendingObservation& observation) noexcept;
    void processFramesImpl(int32_t numFrames, int32_t sampleRate,
                           uint64_t streamEpoch, uint64_t continuityEpoch,
                           uint64_t firstSourceFrame) noexcept;

    std::unique_ptr<TransportHistory> callbackHistory_;
    std::unique_ptr<TransportHistoryChannel> historyChannel_;
    std::unique_ptr<TransportDiagnosticsChannel> diagnosticChannel_;
    std::array<PendingObservation, ObservationCapacity> observations_{};
    std::atomic<uint32_t> observationHead_{0};
    std::atomic<uint32_t> observationTail_{0};
    std::atomic<uint32_t> droppedObservations_{0};
    std::atomic<bool> diagnosticsEnabled_{false};
    std::atomic<uint64_t> capturedFrameWatermark_{0};
    std::atomic<uint64_t> publishedStreamEpoch_{1};
    uint64_t streamEpochRt_ = 1;
    uint64_t legacySourceFrame_ = 0;
    double cumulativeBaseBeatsRt_ = 0.0;
    uint64_t lastAppliedObservationSequence_ = 0;
    uint64_t lastContinuityEpochRt_ = 0;
    uint32_t rejectedObservations_ = 0;

    double expectedBpmRt_ = 120.0;
    double currentBpmRt_ = 120.0;
    double positionRt_ = 0.0;

    std::atomic<double> requestedExpectedBpm_{120.0};
    std::atomic<double> requestedPosition_{0.0};
    std::atomic<uint64_t> positionGeneration_{0};
    uint64_t appliedPositionGeneration_ = 0;

    std::atomic<double> publishedBpm_{120.0};
    std::atomic<double> publishedDetectedBpm_{0.0};
    std::atomic<double> publishedPosition_{0.0};
    std::atomic<double> publishedConfidence_{0.0};
    std::atomic<double> publishedRms_{0.0};
    std::atomic<bool> running_{false};
    std::atomic<double> tempoTargetBpm_{0.0};
    std::atomic<float> tempoTargetConfidence_{0.0f};
    std::atomic<bool> tempoTargetValid_{false};
    std::atomic<uint64_t> tempoVersion_{0};
    std::atomic<double> tempoDetectedBpm_{0.0};
    std::atomic<double> tempoRms_{0.0};
    // Published score-following state (spec §28 fields 6-11).
    std::atomic<double> publishedPositionConfidence_{0.0};
    std::atomic<double> publishedMatchedPosition_{0.0};
    std::atomic<double> publishedPositionError_{0.0};
    std::atomic<int> publishedPositionState_{0};
    std::atomic<double> publishedAmbiguity_{0.0};
    std::atomic<double> publishedValidContextSeconds_{0.0};
    // Single analyzer producer -> audio callback consumer. Payload slots are
    // plain data protected by ownership indices, never concurrent seqlock reads.
    double remainingPositionErrorRt_ = 0.0;
};

} // namespace temposcore
