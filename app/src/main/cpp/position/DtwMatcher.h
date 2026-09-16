#pragma once
#include "position/FeatureRing.h"
#include "position/ScoreReference.h"
#include <array>
namespace temposcore {
enum class PositionTrackingState { Idle, Acquiring, Locked, Weak, Reacquiring };
struct PositionObservation {
    double quarterBeatPosition = 0, positionErrorBeats = 0;
    float matchQuality = 0, confidence = 0, ambiguityMargin = 0;
    bool valid = false, globalMatch = false;
    PositionTrackingState state = PositionTrackingState::Idle;
};
class DtwMatcher final {
public:
    explicit DtwMatcher(const ScoreReference& r) : reference_(r) {}
    PositionObservation global(const FeatureRing& l) noexcept;
    PositionObservation local(const FeatureRing& l, double p) noexcept;
    // Coarse chroma-cosine pre-filter: score candidate starts (stepped by
    // stepFrames) and return the top-K start positions (outCount <= 8).
    void coarseTopK(const FeatureRing& l, size_t stepFrames,
                    std::array<size_t, 8>& out, size_t& outCount) const noexcept;
    // Fine constrained DTW restricted to the given candidate start positions.
    PositionObservation globalOn(const FeatureRing& l, const std::array<size_t, 8>& starts,
                                 size_t count) noexcept;
    // Local constrained DTW. predicted is quarterBeatPosition; radius is seconds
    // on the score reference nominal-time axis.
    PositionObservation localOn(const FeatureRing& l, double predicted, double radiusSec) noexcept;
    static float frameDistance(const AudioFeatureFrame&, const ScoreFeatureFrame&) noexcept;
private:
    PositionObservation search(const FeatureRing& l, double predicted, bool localSearch,
                               double radiusSec, const size_t* startList, size_t startCount) noexcept;
    const ScoreReference& reference_;
    static constexpr size_t MaxBand = 301;
    mutable std::array<AudioFeatureFrame, FeatureRing::Capacity> live_{};
    std::array<float, FeatureRing::Capacity * MaxBand> dp_{};
};
}
