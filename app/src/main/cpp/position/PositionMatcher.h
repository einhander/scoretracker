#pragma once
#include "position/DtwMatcher.h"
#include <array>
namespace temposcore {
// Slow-loop score-position state machine (Milestones B.3/C). Runs on the
// analyzer thread at a ~2 s feature-time cadence (NOT the Oboe callback).
// Consumes the live FeatureRing + ScoreReference and emits a PositionObservation
// (spec §12) that LiveTransport applies as a position correction.
class PositionMatcher final {
public:
    explicit PositionMatcher(const ScoreReference& r) : dtw_(r) {}
    // Reset to a fresh acquisition (call when the user starts listening).
    void begin() noexcept {
        state_ = PositionTrackingState::Acquiring;
        stable_ = 0;
        weakStreak_ = 0;
        hasLast_ = false;
    }
    PositionObservation update(const FeatureRing& f, double predicted = 0.0) noexcept;
    PositionTrackingState state() const noexcept { return state_; }
private:
    // Coarse (0.5 s step) chroma pre-filter -> top-K=8 -> fine DTW.
    PositionObservation acquireGlobal(const FeatureRing& f, double predicted) noexcept;
    // Local DTW with the given radius (seconds).
    PositionObservation trackLocal(const FeatureRing& f, double predicted, double radiusSec) noexcept;
    // Confidence = DTW quality * valid-fraction * (0.7 + 0.3 * stability).
    float computeConfidence(const PositionObservation& o, const FeatureRing& f) const noexcept;
    // Bounded continuity prior: does the new position agree with the last?
    bool positionAgrees(const PositionObservation& o) const noexcept;

    DtwMatcher dtw_;
    PositionTrackingState state_ = PositionTrackingState::Idle;
    int stable_ = 0;       // consecutive agreeing observations (initial-lock stability)
    int weakStreak_ = 0;   // consecutive weak observations (hysteresis before Reacquiring)
    double lastPosition_ = 0.0;
    bool hasLast_ = false;

    static constexpr float kLock = 0.80f;        // enter Locked
    static constexpr float kRelocate = 0.88f;    // allow a hard relocation / initial lock
    static constexpr float kWeakExit = 0.60f;    // leave Locked below this (hysteresis)
    static constexpr int kWeakToReacquire = 3;   // consecutive weak before Reacquiring
    static constexpr double kLocalRadius = 30.0;    // seconds (Locked)
    static constexpr double kLocalRadiusWide = 60.0; // seconds (Weak, widened)
    static constexpr size_t kTopK = 8;
    static constexpr size_t kCoarseStep = 5; // frames (0.5 s at 10 Hz)
};
}
