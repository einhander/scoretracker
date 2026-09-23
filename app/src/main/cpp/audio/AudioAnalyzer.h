#pragma once
#include "audio/SpscAudioRing.h"
#include "dsp/AudioFeatureFrame.h"
#include "dsp/Chroma.h"
#include "dsp/SpectralFlux.h"
#include "dsp/Stft.h"
#include "position/FeatureRing.h"
#include "position/PositionMatcher.h"
#include "transport/LiveTransport.h"
#include "beat/BeatTracker.h"
#include <atomic>
#include <cstdint>
#include <thread>
#include <memory>
namespace temposcore {
class AudioAnalyzer final {
public:
    explicit AudioAnalyzer(SpscAudioRing& ring) noexcept : ring_(ring) {}
    ~AudioAnalyzer() { stop(); }
    bool start(int32_t sampleRate, double expectedBpm);
    void setExpectedBpm(double bpm) noexcept { beatTracker_.setExpectedBpm(bpm); }
    void requestPositionReset(bool local) noexcept {
        resetLocal_.store(local, std::memory_order_relaxed);
        resetRequest_.fetch_add(1, std::memory_order_release);
    }
    void stop() noexcept; // Non-real-time only: join may block.
    uint64_t framesConsumed() const noexcept { return framesConsumed_.load(std::memory_order_relaxed); }
    uint64_t featureSequence() const noexcept { return featureSequence_.load(std::memory_order_acquire); }
    uint64_t appliedResetEpoch() const noexcept { return appliedResetRequest_.load(std::memory_order_acquire); }
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
    std::atomic<uint64_t> resetRequest_{0};
    std::atomic<bool> resetLocal_{false};
    AudioFeatureFrame latestFeature_{};
    std::unique_ptr<Stft> stft_;
    std::unique_ptr<ChromaExtractor> chroma_;
    std::unique_ptr<SpectralFlux> flux_;
    BeatTracker beatTracker_;
    // Slow-loop score following (analyzer thread only, non-RT).
    FeatureRing featureRing_;
    PositionMatcher* matcher_ = nullptr;
    LiveTransport* transport_ = nullptr;
    int64_t lastMatcherCenter_ = 0; // last feature center (samples) the matcher ran
    std::atomic<uint64_t> appliedResetRequest_{0};
    size_t freshFeatureFrames_ = 0;
    int32_t sampleRate_ = 48000;
    double expectedBpm_ = 120.0;
};
}
