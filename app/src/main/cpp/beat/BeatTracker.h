#pragma once

#include <atomic>
#include <cstdint>

namespace temposcore {

struct BeatObservation {
    double detectedBpm = 0.0;
    double phaseCorrectionBeats = 0.0;
    float confidence = 0.0f;
    float rms = 0.0f;
    bool tempoValid = false;
    bool phaseValid = false;
};

/**
 * Lightweight bootstrap tracker, intentionally conservative.
 *
 * It uses short-time energy rises as onset candidates, normalises candidate inter-onset
 * tempi around the MIDI prior, and applies a small PLL-like phase correction only when an
 * onset lands close to the predicted beat grid.
 *
 * This is NOT the production algorithm. Replace it with spectral-flux/multiband onset
 * detection + a probabilistic beat tracker without changing the JNI/UI contract.
 */
class BeatTracker {
public:
    void configure(double expectedBpm, int32_t sampleRate) noexcept;
    // Non-resetting expected-BPM update (main thread; may be called while the
    // stream is running). Only touches expectedBpm_ (atomic) — the running beat
    // PLL (phase, confidence, estimated bpm) is preserved.
    void setExpectedBpm(double bpm) noexcept;
    BeatObservation process(const float* interleaved,
                            int32_t numFrames,
                            int32_t channelCount) noexcept;

private:
    double normaliseCandidateBpm(double bpm) const noexcept;

    std::atomic<double> expectedBpm_{120.0};
    double estimatedBpm_ = 120.0;
    int32_t sampleRate_ = 48000;
    int64_t framePosition_ = 0;
    int64_t lastOnsetFrame_ = -1;
    double predictedNextBeatFrame_ = -1.0;

    float previousRms_ = 0.0f;
    float fluxEma_ = 0.0005f;
    float confidence_ = 0.0f;
};

} // namespace temposcore
