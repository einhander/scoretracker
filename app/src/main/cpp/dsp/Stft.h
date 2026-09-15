#pragma once
#include "dsp/Fft.h"
#include <complex>
#include <cstdint>
#include <vector>
namespace temposcore {
class Stft final {
public:
    Stft(int sampleRate, size_t fftSize=4096, size_t hop=1024);
    bool process(const float* samples, size_t count) noexcept;
    const std::vector<float>& magnitude() const noexcept { return magnitude_; }
    const std::vector<float>& windowed() const noexcept { return window_; }
    const std::vector<float>& window() const noexcept { return window_; }
    int sampleRate() const noexcept { return sampleRate_; }
    size_t fftSize() const noexcept { return fftSize_; }
    size_t hop() const noexcept { return hop_; }
    uint64_t frameIndex() const noexcept { return frameIndex_; }
    float frameEnergy() const noexcept { return frameEnergy_; }
private:
    int sampleRate_; size_t fftSize_, hop_, fill_=0; uint64_t frameIndex_=0;
    float frameEnergy_=0.0f;
    Fft fft_; std::vector<float> samples_, window_, magnitude_; std::vector<std::complex<float>> spectrum_;
};
}
