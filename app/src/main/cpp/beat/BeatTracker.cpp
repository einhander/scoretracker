#include "beat/BeatTracker.h"

#include <algorithm>
#include <cmath>

namespace temposcore {
namespace {
constexpr float kWeights[3] = {0.8f, 1.0f, 0.7f};
constexpr float kEpsilon = 1.0e-5f;
}

void BeatTracker::configure(double expectedBpm, int32_t sampleRate, int32_t hopSize) noexcept {
    if (!std::isfinite(expectedBpm)) expectedBpm = 0.0;
    expectedBpm_.store(std::clamp(expectedBpm, 30.0, 300.0), std::memory_order_relaxed);
    sampleRate_ = std::max(sampleRate, 8000);
    hopSize_ = std::max(hopSize, 1);
    featureRate_ = static_cast<double>(sampleRate_) / hopSize_;
    history_.fill(0.0f); mean_.fill(0.0f); deviation_.fill(0.01f);
    historySize_ = historyWrite_ = 0;
    estimatedBpm_ = 0.0; confidence_ = 0.0f; activity_ = 0.0f;
    evaluationCountdown_ = 0;
    lastCenterFrame_ = 0;
}

void BeatTracker::setExpectedBpm(double bpm) noexcept {
    if (!std::isfinite(bpm)) return;
    expectedBpm_.store(std::clamp(bpm, 30.0, 300.0), std::memory_order_relaxed);
}

double BeatTracker::evaluateTempo() noexcept {
    if (historySize_ < static_cast<std::size_t>(featureRate_ * 3.0) || activity_ < 0.01f) return 0.0;
    const int maxLag = std::min( static_cast<int>(historySize_) - 1,
                                 static_cast<int>(featureRate_ * 60.0 / 40.0));
    const int minLag = std::max(1, static_cast<int>(featureRate_ * 60.0 / 240.0));
    const std::size_t window = std::min(historySize_, std::min(history_.size(),
        static_cast<std::size_t>(std::max(2.0, std::round(featureRate_ * 8.0)))));
    const int windowLag = static_cast<int>(window) - 1;
    const int boundedMinLag = std::min(minLag, windowLag);
    const int boundedMaxLag = std::min(maxLag, windowLag);
    if (boundedMinLag >= boundedMaxLag) return 0.0;
    std::array<double, 1024> acValues{};
    std::array<double, 1024> scores{};
    std::array<int, 24> peaks{};
    int peakCount = 0;
    auto correlationAt = [&](int l) {
        if (l >= static_cast<int>(window)) return 0.0;
        double a = 0.0, b = 0.0, c = 0.0;
        for (std::size_t j = l; j < window; ++j) {
            const float x = history_[(historyWrite_ + history_.size() - window + j) % history_.size()];
            const float y = history_[(historyWrite_ + history_.size() - window + j - l) % history_.size()];
            a += x * y; b += x * x; c += y * y;
        }
        return a / (std::sqrt(b * c) + kEpsilon);
    };
    for (int lag = boundedMinLag; lag <= boundedMaxLag; ++lag) {
        double xy = 0.0, xx = 0.0, yy = 0.0;
        const std::size_t n = window;
        for (std::size_t i = lag; i < n; ++i) {
            const float x = history_[(historyWrite_ + history_.size() - n + i) % history_.size()];
            const float y = history_[(historyWrite_ + history_.size() - n + i - lag) % history_.size()];
            xy += x * y; xx += x * x; yy += y * y;
        }
        const double ac = xy / (std::sqrt(xx * yy) + kEpsilon);
        acValues[lag] = ac;
    }
    // Discover peaks from raw autocorrelation first; priors cannot invent peaks.
    for (int lag = boundedMinLag + 1; lag < boundedMaxLag; ++lag)
        if (acValues[lag] >= acValues[lag - 1] && acValues[lag] >= acValues[lag + 1] && peakCount < 24)
            peaks[peakCount++] = lag;
    for (int p = 0; p < peakCount; ++p) {
        const int lag = peaks[p];
        const double bpm = featureRate_ * 60.0 / lag;
        const double distance = std::abs(std::log2(bpm / expectedBpm_.load(std::memory_order_relaxed)));
        const double ac = acValues[lag];
        const double family = ac + 0.35 * correlationAt(lag * 2) + 0.20 * correlationAt(lag * 4);
        scores[lag] = family + 0.15 * std::exp(-0.5 * std::pow(distance / 0.5, 2.0));
    }
    double best = 0.0, second = 0.0, chosen = 0.0;
    for (int p = 0; p < peakCount; ++p) {
        const double score = scores[peaks[p]];
        if (score > best) { second = best; best = score; chosen = featureRate_ * 60.0 / peaks[p]; }
        else if (score > second) second = score;
    }
    const float context = std::min(1.0f, static_cast<float>(historySize_ / (featureRate_ * 6.0)));
    const double uniqueness = peakCount >= 2 ? (best - second) : 0.0;
    confidence_ = std::clamp(static_cast<float>(0.45 * best + 0.9 * uniqueness + 0.35 * context), 0.0f, 1.0f);
    if (confidence_ < 0.28f) return 0.0;
    if (estimatedBpm_ <= 0.0) estimatedBpm_ = chosen;
    else estimatedBpm_ = std::exp(0.75 * std::log(estimatedBpm_) + 0.25 * std::log(chosen));
    return estimatedBpm_;
}

BeatObservation BeatTracker::processFlux(const std::array<float, 3>& bands, float energy,
                                         int64_t centerAudioFrame) noexcept {
    BeatObservation out;
    float onset = 0.0f;
    for (int i = 0; i < 3; ++i) {
        const float x = std::isfinite(bands[i]) ? std::max(0.0f, bands[i]) : 0.0f;
        mean_[i] = 0.99f * mean_[i] + 0.01f * x;
        deviation_[i] = 0.99f * deviation_[i] + 0.01f * std::abs(x - mean_[i]);
        onset += kWeights[i] * std::clamp((x - mean_[i]) / (deviation_[i] + kEpsilon), 0.0f, 8.0f);
    }
    const float safeEnergy = std::isfinite(energy) ? std::max(0.0f, energy) : 0.0f;
    activity_ = 0.995f * activity_ + 0.005f * std::min(1.0f, std::max(onset / 4.0f, safeEnergy * 10.0f));
    history_[historyWrite_] = onset;
    historyWrite_ = (historyWrite_ + 1) % history_.size();
    historySize_ = std::min(historySize_ + 1, history_.size());
    if (--evaluationCountdown_ <= 0) { evaluateTempo(); evaluationCountdown_ = std::max(1, static_cast<int>(featureRate_ / 4.0)); }
    if (activity_ < 0.02f) { estimatedBpm_ = 0.0; confidence_ = 0.0f; }
    out.detectedBpm = estimatedBpm_; out.confidence = confidence_; out.rms = std::sqrt(safeEnergy);
    out.tempoValid = estimatedBpm_ > 0.0 && confidence_ >= 0.28f;
    out.phaseValid = false; out.phaseCorrectionBeats = 0.0;
    lastCenterFrame_ = centerAudioFrame;
    return out;
}
} // namespace temposcore
