#pragma once
#include "dsp/AudioFeatureFrame.h"
#include <array>
#include <cstdint>
#include <vector>
namespace temposcore {
struct MidiNoteEvent { int channel=0,pitch=60,velocity=100; int64_t startTick=0,endTick=0; };
struct MidiTempoEvent { int64_t tick=0; int microsecondsPerQuarter=500000; };
struct MidiData { int ppq=480; int64_t totalTicks=0; std::vector<MidiNoteEvent> notes; std::vector<MidiTempoEvent> tempos; };
struct ScoreFeatureFrame { std::array<float,12> chroma{}; float onset=0; double quarterBeatPosition=0; double nominalSeconds=0; };
class ScoreReference final { public: const std::vector<ScoreFeatureFrame>& frames() const noexcept{return frames_;} void clear(){frames_.clear();} private: std::vector<ScoreFeatureFrame> frames_; friend bool buildScoreReference(const MidiData&,ScoreReference&); };
bool buildScoreReference(const MidiData& midi, ScoreReference& out);
}
