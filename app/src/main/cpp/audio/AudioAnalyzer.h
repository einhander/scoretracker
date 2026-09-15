#pragma once
#include "audio/SpscAudioRing.h"
#include "dsp/AudioFeatureFrame.h"
#include "dsp/Chroma.h"
#include "dsp/SpectralFlux.h"
#include "dsp/Stft.h"
#include <atomic>
#include <cstdint>
#include <thread>
#include <memory>
namespace temposcore {
class AudioAnalyzer final {
public:
    explicit AudioAnalyzer(SpscAudioRing& ring) noexcept : ring_(ring) {}
    ~AudioAnalyzer() { stop(); }
    bool start(int32_t sampleRate);
    void stop() noexcept; // Non-real-time only: join may block.
    uint64_t framesConsumed() const noexcept { return framesConsumed_.load(std::memory_order_relaxed); }
    uint64_t featureSequence() const noexcept { return featureSequence_.load(std::memory_order_acquire); }
    // Callers must retry in a loop when this returns false.
    bool latestFeature(AudioFeatureFrame& out) const noexcept;
private:
    void run(int32_t sampleRate) noexcept;
    int64_t nextGridCenter_ = 2048; // 10 Hz publish grid; written by start() + worker only.
    SpscAudioRing& ring_;
    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<uint64_t> framesConsumed_{0};
    std::atomic<uint64_t> featureSequence_{0};
    AudioFeatureFrame latestFeature_{};
    std::unique_ptr<Stft> stft_;
    std::unique_ptr<ChromaExtractor> chroma_;
    std::unique_ptr<SpectralFlux> flux_;
};
}
