#pragma once
#include "position/FeatureRing.h"
#include "position/ScoreReference.h"
#include <array>
namespace temposcore { struct PositionObservation { double quarterBeatPosition=0,positionErrorBeats=0;float matchQuality=0,confidence=0,ambiguityMargin=0;bool valid=false,globalMatch=false;}; class DtwMatcher final { public: explicit DtwMatcher(const ScoreReference&r):reference_(r){} PositionObservation global(const FeatureRing&live) noexcept; PositionObservation local(const FeatureRing&live,double predicted) noexcept; static float frameDistance(const AudioFeatureFrame&,const ScoreFeatureFrame&) noexcept; private: PositionObservation search(const FeatureRing&,double,bool) noexcept; const ScoreReference&reference_; static constexpr size_t MaxBand=301; std::array<AudioFeatureFrame,FeatureRing::Capacity> live_{}; std::array<float,FeatureRing::Capacity*MaxBand> dp_{};}; }
