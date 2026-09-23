#include "position/PositionMatcher.h"
#include <algorithm>
#include <cmath>
namespace temposcore {
bool PositionMatcher::positionAgrees(const PositionObservation& o, double predicted) const noexcept {
    if (!hasLast_) return true;
    const double expectedNow = lastPosition_ + (predicted - lastPredicted_);
    return std::abs(o.quarterBeatPosition - expectedNow) <= 1.0; // within 1 beat of expected progression
}
float PositionMatcher::computeConfidence(const PositionObservation& o, const FeatureRing& f,
                                         double predicted) const noexcept {
    if (!o.valid) return 0.0f;
    // Base: the DTW confidence (matchQuality * window-fill * margin factor).
    float base = o.confidence;
    // Valid-frame fraction of the live window.
    const size_t cnt = f.size();
    if (cnt > 0) {
        std::array<AudioFeatureFrame, FeatureRing::Capacity> tmp{};
        const size_t n = f.copy(tmp);
        size_t valid = 0;
        for (size_t i = 0; i < n; ++i) if (tmp[i].valid) ++valid;
        base *= static_cast<float>(valid) / static_cast<float>(n);
    }
    // Stability: bounded continuity prior (agreement with the previous position).
    float stability = 1.0f;
    if (hasLast_) {
        const double expectedNow = lastPosition_ + (predicted - lastPredicted_);
        const double err = std::abs(o.quarterBeatPosition - expectedNow);
        stability = err <= 1.0 ? 1.0f : static_cast<float>(std::max(0.0, 1.0 - (err - 1.0) / 3.0));
    }
    return base * (0.7f + 0.3f * stability);
}
PositionObservation PositionMatcher::acquireGlobal(const FeatureRing& f, double predicted) noexcept {
    (void)predicted; // global search is not biased by the prediction
    std::array<size_t, 8> starts{};
    size_t count = 0;
    dtw_.coarseTopK(f, kCoarseStep, starts, count);
    if (count == 0) {
        PositionObservation o;
        o.state = state_;
        return o;
    }
    PositionObservation o = dtw_.globalOn(f, starts, count);
    o.state = state_;
    return o;
}
PositionObservation PositionMatcher::trackLocal(const FeatureRing& f, double predicted, double radiusSec) noexcept {
    PositionObservation o = dtw_.localOn(f, predicted, radiusSec);
    o.state = state_;
    return o;
}
PositionObservation PositionMatcher::update(const FeatureRing& f, double predicted,
                                            uint64_t generation) noexcept {
    if (state_ == PositionTrackingState::Idle) begin();
    // Main-thread requests are applied here, on the analyzer thread only.
    // A manual scrub keeps the user's chosen neighbourhood authoritative and
    // resumes with local DTW; Reset/test seek still asks for a global search.
    if (localReacquireRequested_.exchange(false, std::memory_order_acquire)) {
        state_ = PositionTrackingState::Reacquiring;
        localReacquiring_ = true;
        stable_ = 0;
        weakStreak_ = 0;
        hasLast_ = false;
    } else if (reacquireRequested_.exchange(false, std::memory_order_acquire)) {
        begin();
    }
    PositionObservation o;
    if (state_ == PositionTrackingState::Locked || state_ == PositionTrackingState::Weak ||
        (state_ == PositionTrackingState::Reacquiring && localReacquiring_)) {
        const double radius = (state_ == PositionTrackingState::Weak) ? kLocalRadiusWide : kLocalRadius;
        o = trackLocal(f, predicted, radius);
    } else {
        o = acquireGlobal(f, predicted);
    }
    const bool agrees = positionAgrees(o, predicted);
    const float conf = computeConfidence(o, f, predicted);
    o.confidence = conf;
    // Re-derive validity from the FINAL confidence (not the raw DTW confidence),
    // so the transport's correction gate and the "strong" check agree.
    o.valid = (conf >= 0.5f);

    if (conf >= kLock && o.ambiguityMargin > 0.05f) {
        // Strong + unambiguous.
        if (state_ == PositionTrackingState::Locked || state_ == PositionTrackingState::Weak) {
            state_ = PositionTrackingState::Locked;
            weakStreak_ = 0;
            stable_ = agrees ? stable_ + 1 : 0;
        } else {
            // Acquiring / Reacquiring: initial global lock.
            const bool veryStrongUnique = (conf >= kRelocate) && (o.ambiguityMargin > 0.2f);
            const bool twoConsecutive = (stable_ >= 1) && agrees;
            if (!localReacquiring_ && (veryStrongUnique || twoConsecutive)) {
                state_ = PositionTrackingState::Locked;
                stable_ = veryStrongUnique ? 1 : 2;
            } else {
                stable_ = agrees ? stable_ + 1 : 0;
                if (localReacquiring_ && stable_ >= 2 && veryStrongUnique) {
                    state_ = PositionTrackingState::Locked;
                    localReacquiring_ = false;
                }
            }
        }
    } else if (conf < kWeakExit) {
        // Weak.
        if (state_ == PositionTrackingState::Locked) {
            state_ = PositionTrackingState::Weak;
            weakStreak_ = 1;
        } else if (state_ == PositionTrackingState::Weak) {
            if (++weakStreak_ >= kWeakToReacquire) state_ = PositionTrackingState::Reacquiring;
        } else {
            stable_ = 0;
        }
    } else {
        // Mid confidence: keep the state, track stability, recover from Weak.
        if (state_ == PositionTrackingState::Weak && conf >= 0.7f) weakStreak_ = 0;
        stable_ = agrees ? stable_ + 1 : 0;
    }

    // Only a valid observation updates the continuity prior; an invalid one
    // (quarterBeatPosition=0) must not poison the next "does it agree?" check.
    if (o.valid) {
        lastPosition_ = o.quarterBeatPosition;
        lastPredicted_ = predicted;
        hasLast_ = true;
    }
    o.state = state_;
    if (localReacquiring_) o.valid = false;
    o.resetGeneration = generation;
    return o;
}
}
