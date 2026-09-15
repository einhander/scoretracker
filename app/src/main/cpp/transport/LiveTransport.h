#pragma once

#include "beat/BeatTracker.h"
#include "position/DtwMatcher.h"

#include <atomic>
#include <cstdint>

namespace temposcore {

struct TransportState {
    double transportBpm = 120.0;
    double detectedBpm = 0.0;
    double quarterBeatPosition = 0.0;
    double confidence = 0.0;
    double rms = 0.0;
    bool running = false;
};

class LiveTransport {
public:
    void configure(double expectedBpm, double startQuarterBeat) noexcept;
    void resetPosition(double startQuarterBeat) noexcept;
    void setExpectedBpm(double expectedBpm) noexcept;
    void setRunning(bool running) noexcept;
    void submitPositionObservation(const PositionObservation&) noexcept;
    void processFrames(int32_t numFrames,
                       int32_t sampleRate,
                       const BeatObservation& observation) noexcept;
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
    // Persistent position-correction target (single writer = analyzer thread via
    // submitPositionObservation, single reader = processFrames on the Oboe
    // callback). The target stays active and is slewed toward on EVERY callback
    // (rate-limited) until the cursor reaches it (|err|<0.5) or it is rejected
    // (large + ambiguous). targetActive_ is the release/acquire handshake.
    std::atomic<double> targetPosition_{0.0}, targetConfidence_{0.0}, targetAmbiguity_{1.0};
    std::atomic<bool> targetActive_{false}, targetGlobal_{false};
};

} // namespace temposcore
