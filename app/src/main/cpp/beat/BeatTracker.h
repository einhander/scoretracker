#pragma once

#include <array>
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

/** Fixed-storage multiband spectral-flux tempo estimator. Phase remains intentionally disabled. */
class BeatTracker {
public:
    void configure(double expectedBpm, int32_t sampleRate, int32_t hopSize) noexcept;
    // Non-resetting expected-BPM update (main thread; may be called while the
    // stream is running). Only prior changes; running estimate is preserved.
    void setExpectedBpm(double bpm) noexcept;
    BeatObservation processFlux(const std::array<float, 3>& bands,
                                float energy,
                                int64_t centerAudioFrame) noexcept;

private:
    double evaluateTempo() noexcept;

    std::atomic<double> expectedBpm_{120.0};
    double estimatedBpm_ = 0.0;
    int32_t sampleRate_ = 48000;
    int32_t hopSize_ = 1024;
    double featureRate_ = 46.875;
    // 384 samples at 46.875 Hz: 8.19-second analysis window.
    std::array<float, 1024> history_{};
    std::size_t historySize_ = 0;
    std::size_t historyWrite_ = 0;
    std::array<float, 3> mean_{};
    std::array<float, 3> deviation_{};
    int64_t lastCenterFrame_ = 0;
    int32_t evaluationCountdown_ = 0;
    float activity_ = 0.0f;
    float confidence_ = 0.0f;
};

} // namespace temposcore
