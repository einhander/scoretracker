#pragma once

#include <cstddef>
#include <cstdint>

namespace temposcore {

// Rational 10 Hz deadline scheduler on a sample-frame axis. Phase 1 frame
// numbers are analyzer-local; phase 2 must map them to captured PCM time. A caller
// offers each STFT center once; when due, this returns true once and advances
// past every missed deadline without synthesizing duplicate feature windows.
class FeatureGrid final {
public:
    static constexpr int64_t RateHz = 10;

    FeatureGrid() = default;
    FeatureGrid(int32_t sampleRate, int64_t firstCenter) noexcept { reset(sampleRate, firstCenter); }

    void reset(int32_t sampleRate, int64_t firstCenter) noexcept {
        sampleRate_ = sampleRate > 0 ? sampleRate : 1;
        nextDeadlineTimesTen_ = firstCenter * RateHz;
        initialized_ = true;
    }

    bool due(int64_t centerFrame, uint64_t* skippedDeadlines = nullptr) noexcept {
        if (skippedDeadlines) *skippedDeadlines = 0;
        if (!initialized_ || centerFrame * RateHz < nextDeadlineTimesTen_) return false;
        const int64_t intervals = (centerFrame * RateHz - nextDeadlineTimesTen_) / sampleRate_ + 1;
        nextDeadlineTimesTen_ += intervals * sampleRate_;
        if (skippedDeadlines) *skippedDeadlines = static_cast<uint64_t>(intervals - 1);
        return true;
    }

    int32_t sampleRate() const noexcept { return sampleRate_; }

private:
    int32_t sampleRate_ = 1;
    int64_t nextDeadlineTimesTen_ = 0;
    bool initialized_ = false;
};

// Shared slow-loop cadence rule so host tests exercise the same warmup and
// sample-time threshold as AudioAnalyzer rather than a copied approximation.
class FeatureMatcherCadence final {
public:
    static constexpr size_t MinimumFreshFeatures = 20;

    void reset() noexcept {
        lastCenterFrame_ = 0;
        originCenterFrame_ = 0;
        hasOrigin_ = false;
        hasRun_ = false;
    }
    bool due(int64_t centerFrame, int32_t sampleRate, std::size_t freshFeatures) noexcept {
        if (sampleRate <= 0) return false;
        if (!hasOrigin_) {
            originCenterFrame_ = centerFrame;
            hasOrigin_ = true;
        }
        const int64_t reference = hasRun_ ? lastCenterFrame_ : originCenterFrame_;
        return freshFeatures >= MinimumFreshFeatures && centerFrame >= reference &&
               centerFrame - reference >= 2LL * sampleRate;
    }
    int64_t intervalFrames(int64_t centerFrame) const noexcept {
        return centerFrame - (hasRun_ ? lastCenterFrame_ : originCenterFrame_);
    }
    void markRun(int64_t centerFrame) noexcept { lastCenterFrame_ = centerFrame; hasRun_ = true; }

private:
    int64_t lastCenterFrame_ = 0;
    int64_t originCenterFrame_ = 0;
    bool hasOrigin_ = false;
    bool hasRun_ = false;
};

} // namespace temposcore
