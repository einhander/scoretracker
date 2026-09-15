#pragma once
#include "audio/SpscAudioRing.h"
#include "dsp/AudioFeatureFrame.h"
#include "dsp/Chroma.h"
#include "dsp/SpectralFlux.h"
#include "dsp/Stft.h"
#include "position/FeatureRing.h"
#include "position/PositionMatcher.h"
#include "transport/LiveTransport.h"
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
    // Wire the slow-loop score follower. Called on the main thread BEFORE start()
    // (the worker thread only reads these pointers). When matcher_ is set, the
    // analyzer accumulates the 10 Hz feature frames into a FeatureRing and runs
    // the PositionMatcher on a ~2 s feature-time (sample-frame) cadence, handing
    // each PositionObservation to the LiveTransport.
    void configureMatcher(PositionMatcher* matcher, LiveTransport* transport) noexcept {
        matcher_ = matcher;
        transport_ = transport;
    }
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
    // Slow-loop score following (analyzer thread only, non-RT).
    FeatureRing featureRing_;
    PositionMatcher* matcher_ = nullptr;
    LiveTransport* transport_ = nullptr;
    int64_t lastMatcherCenter_ = 0; // last feature center (samples) the matcher ran
};
}
