#pragma once
#include "position/DtwMatcher.h"
#include <array>
#include <atomic>
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
    // Request a global re-acquisition from the main thread (JNI). The analyzer
    // thread applies it on its next update() (no cross-thread state write).
    void requestReacquire() noexcept {
        localReacquireRequested_.store(false, std::memory_order_release);
        reacquireRequested_.store(true, std::memory_order_release);
    }
    // Manual score scrub: trust the user-selected neighbourhood and resume with
    // local DTW instead of immediately performing a global section search.
    void requestLocalReacquire() noexcept {
        reacquireRequested_.store(false, std::memory_order_release);
        localReacquireRequested_.store(true, std::memory_order_release);
    }
    PositionObservation update(const FeatureRing& f, double predicted = 0.0) noexcept;
    PositionTrackingState state() const noexcept { return state_; }
private:
    // Coarse (0.5 s step) chroma pre-filter -> top-K=8 -> fine DTW.
    PositionObservation acquireGlobal(const FeatureRing& f, double predicted) noexcept;
    // Local DTW with the given radius (seconds).
    PositionObservation trackLocal(const FeatureRing& f, double predicted, double radiusSec) noexcept;
    // Confidence = DTW quality * valid-fraction * (0.7 + 0.3 * stability).
    float computeConfidence(const PositionObservation& o, const FeatureRing& f,
                            double predicted) const noexcept;
    // Bounded continuity prior: compare against the expected progression since
    // the previous observation, not against the previous absolute position.
    bool positionAgrees(const PositionObservation& o, double predicted) const noexcept;

    DtwMatcher dtw_;
    std::atomic<bool> reacquireRequested_{false}; // main thread -> analyzer thread
    std::atomic<bool> localReacquireRequested_{false};
    PositionTrackingState state_ = PositionTrackingState::Idle;
    int stable_ = 0;       // consecutive agreeing observations (initial-lock stability)
    int weakStreak_ = 0;   // consecutive weak observations (hysteresis before Reacquiring)
    double lastPosition_ = 0.0;
    double lastPredicted_ = 0.0;
    bool hasLast_ = false;

    static constexpr float kLock = 0.80f;        // enter Locked
    static constexpr float kRelocate = 0.88f;    // allow a hard relocation / initial lock
    static constexpr float kWeakExit = 0.60f;    // leave Locked below this (hysteresis)
    static constexpr int kWeakToReacquire = 3;   // consecutive weak before Reacquiring
    static constexpr double kLocalRadius = 8.0;     // seconds (Locked: stay near the running path)
    static constexpr double kLocalRadiusWide = 20.0; // seconds (Weak, widened before global reacquire)
    static constexpr size_t kTopK = 8;
    static constexpr size_t kCoarseStep = 5; // frames (0.5 s at 10 Hz)
};
}
