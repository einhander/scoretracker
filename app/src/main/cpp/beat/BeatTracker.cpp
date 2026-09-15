#include "beat/BeatTracker.h"

#include <algorithm>
#include <cmath>

namespace temposcore {

void BeatTracker::configure(double expectedBpm, int32_t sampleRate) noexcept {
    const double clamped = std::clamp(expectedBpm, 30.0, 300.0);
    expectedBpm_.store(clamped, std::memory_order_relaxed);
    estimatedBpm_ = clamped;
    sampleRate_ = std::max(sampleRate, 8000);
    framePosition_ = 0;
    lastOnsetFrame_ = -1;
    predictedNextBeatFrame_ = -1.0;
    previousRms_ = 0.0f;
    fluxEma_ = 0.0005f;
    confidence_ = 0.0f;
}

void BeatTracker::setExpectedBpm(double bpm) noexcept {
    // Non-resetting: only the prior changes; the running beat PLL (phase,
    // confidence, estimated bpm) is preserved. Safe from the main thread while
    // the Oboe callback runs (expectedBpm_ is atomic).
    expectedBpm_.store(std::clamp(bpm, 30.0, 300.0), std::memory_order_relaxed);
}

double BeatTracker::normaliseCandidateBpm(double bpm) const noexcept {
    const double expected = expectedBpm_.load(std::memory_order_relaxed);
    if (!(bpm > 0.0)) return 0.0;
    while (bpm < expected * 0.67) bpm *= 2.0;
    while (bpm > expected * 1.50) bpm *= 0.5;
    return bpm;
}

BeatObservation BeatTracker::process(const float* data,
                                     int32_t numFrames,
                                     int32_t channelCount) noexcept {
    BeatObservation out;
    if (data == nullptr || numFrames <= 0 || channelCount <= 0) return out;

    const double expected = expectedBpm_.load(std::memory_order_relaxed);
    double sumSquares = 0.0;
    const int64_t sampleCount = static_cast<int64_t>(numFrames) * channelCount;
    for (int64_t i = 0; i < sampleCount; ++i) {
        const float x = data[i];
        sumSquares += static_cast<double>(x) * x;
    }
    const float rms = static_cast<float>(std::sqrt(sumSquares / std::max<int64_t>(1, sampleCount)));
    out.rms = rms;

    // Energy-rise onset proxy. A production tracker should replace this with multiband
    // spectral flux or another transient detector robust to guitar/bass/drum mixtures.
    const float positiveRise = std::max(0.0f, rms - previousRms_);
    previousRms_ = 0.82f * previousRms_ + 0.18f * rms;
    fluxEma_ = 0.985f * fluxEma_ + 0.015f * positiveRise;

    const float threshold = std::max(0.0015f, fluxEma_ * 2.8f);
    const int64_t onsetFrame = framePosition_ + numFrames / 2;
    const int64_t refractoryFrames = static_cast<int64_t>(sampleRate_ * 0.075);
    const bool refractoryOk = lastOnsetFrame_ < 0 || onsetFrame - lastOnsetFrame_ > refractoryFrames;
    const bool onset = positiveRise > threshold && rms > 0.006f && refractoryOk;

    if (onset) {
        if (lastOnsetFrame_ >= 0) {
            const int64_t delta = onsetFrame - lastOnsetFrame_;
            if (delta > 0) {
                double candidate = 60.0 * static_cast<double>(sampleRate_) / static_cast<double>(delta);
                candidate = normaliseCandidateBpm(candidate);
                if (candidate >= expected * 0.55 && candidate <= expected * 1.80) {
                    estimatedBpm_ = 0.86 * estimatedBpm_ + 0.14 * candidate;
                    confidence_ = std::min(1.0f, confidence_ + 0.10f);
                    out.tempoValid = true;
                } else {
                    confidence_ *= 0.96f;
                }
            }
        }

        const double beatPeriodFrames = 60.0 * sampleRate_ / std::max(30.0, estimatedBpm_);
        if (predictedNextBeatFrame_ < 0.0) {
            predictedNextBeatFrame_ = onsetFrame + beatPeriodFrames;
        } else {
            // Bring prediction forward until it is around this onset.
            while (predictedNextBeatFrame_ + 0.5 * beatPeriodFrames < onsetFrame) {
                predictedNextBeatFrame_ += beatPeriodFrames;
            }
            const double errorFrames = onsetFrame - predictedNextBeatFrame_;
            if (std::abs(errorFrames) < 0.18 * beatPeriodFrames) {
                const double errorBeats = errorFrames / beatPeriodFrames;
                out.phaseCorrectionBeats = std::clamp(errorBeats * 0.20, -0.04, 0.04);
                out.phaseValid = true;
                predictedNextBeatFrame_ += beatPeriodFrames + errorFrames * 0.15;
                confidence_ = std::min(1.0f, confidence_ + 0.05f);
            }
        }
        lastOnsetFrame_ = onsetFrame;
    } else {
        confidence_ *= 0.9995f;
    }

    framePosition_ += numFrames;
    out.detectedBpm = estimatedBpm_;
    out.confidence = confidence_;
    return out;
}

} // namespace temposcore
