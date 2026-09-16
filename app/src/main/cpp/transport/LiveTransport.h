#pragma once

#include "beat/BeatTracker.h"
#include "position/DtwMatcher.h"

#include <atomic>
#include <cstdint>

namespace temposcore {

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
    void configure(double expectedBpm, double startQuarterBeat) noexcept;
    void resetPosition(double startQuarterBeat) noexcept;
    void setExpectedBpm(double expectedBpm) noexcept;
    void setRunning(bool running) noexcept;
    // Publish an Idle score-following state (main thread, on stop) so the UI
    // does not keep showing a stale LOCKED/Weak state after Stop (spec §30).
    void publishIdle() noexcept;
    // Publish the latest score-following observation (analyzer thread). The
    // validContextSeconds is the number of valid seconds in the live feature
    // window (spec §28 field 11); the analyzer owns the FeatureRing.
    void submitPositionObservation(const PositionObservation&, double validContextSeconds) noexcept;
    void submitTempoObservation(const BeatObservation& observation) noexcept;
    void processFrames(int32_t numFrames, int32_t sampleRate) noexcept;
    TransportState snapshot() const noexcept;

private:
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
    // Persistent position-correction target (single writer = analyzer thread via
    // submitPositionObservation, single reader = processFrames on the Oboe
    // callback). The target stays active and is slewed toward on EVERY callback
    // (rate-limited) until the cursor reaches it (|err|<0.5) or it is rejected
    // (large + ambiguous). targetActive_ is the release/acquire handshake.
    std::atomic<double> targetPosition_{0.0}, targetConfidence_{0.0}, targetAmbiguity_{1.0};
    std::atomic<bool> targetActive_{false}, targetGlobal_{false};
};

} // namespace temposcore
