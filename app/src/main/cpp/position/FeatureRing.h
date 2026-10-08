#pragma once
#include "dsp/AudioFeatureFrame.h"
#include <array>
#include <cstddef>
#include <cstdint>

namespace temposcore {

class FeatureRing final {
public:
    static constexpr size_t Capacity = 200;

    bool push(const AudioFeatureFrame& frame) noexcept {
        frames_[write_ % Capacity] = frame;
        ++write_;
        if (size() < Capacity) ++count_;
        return true;
    }
    size_t size() const noexcept { return count_; }
    void clear() noexcept { write_ = 0; count_ = 0; }
    void setSampleRate(int32_t sampleRate) noexcept { sampleRate_ = sampleRate > 0 ? sampleRate : 0; }
    int32_t sampleRate() const noexcept { return sampleRate_; }
    int64_t firstCenterAudioFrame() const noexcept {
        return count_ ? frames_[(write_ - count_) % Capacity].centerAudioFrame : 0;
    }
    int64_t lastCenterAudioFrame() const noexcept {
        return count_ ? frames_[(write_ - 1) % Capacity].centerAudioFrame : 0;
    }
    double durationSeconds() const noexcept {
        if (count_ < 2 || sampleRate_ <= 0) return 0.0;
        const int64_t span = lastCenterAudioFrame() - firstCenterAudioFrame();
        return span > 0 ? static_cast<double>(span) / sampleRate_ : 0.0;
    }
    size_t copy(std::array<AudioFeatureFrame, Capacity>& out) const noexcept {
        const size_t n = count_;
        for (size_t i = 0; i < n; ++i) out[i] = frames_[(write_ - n + i) % Capacity];
        return n;
    }

private:
    std::array<AudioFeatureFrame, Capacity> frames_{};
    size_t write_ = 0;
    size_t count_ = 0;
    int32_t sampleRate_ = 0;
};

} // namespace temposcore
