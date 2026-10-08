#pragma once
#include "audio/SpscAudioRing.h"
#include "audio/FeatureGrid.h"
#include "dsp/AudioFeatureFrame.h"
#include "dsp/Chroma.h"
#include "dsp/SpectralFlux.h"
#include "dsp/Stft.h"
#include "position/FeatureRing.h"
#include "position/PositionMatcher.h"
#include "transport/LiveTransport.h"
#include "beat/BeatTracker.h"
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <thread>
#include <memory>
namespace temposcore {
static_assert(std::atomic<uint64_t>::is_always_lock_free && std::atomic<double>::is_always_lock_free,
              "Analyzer diagnostic atomics must be lock-free on supported Android ABIs");
struct AudioAnalyzerDiagnostics {
    int32_t sampleRate = 0;
    uint64_t streamEpoch = 0;
    uint64_t framesConsumed = 0;
    uint64_t capturedFrames = 0;
    uint64_t writtenFrames = 0;
    uint64_t consumedFrames = 0;
    uint64_t droppedFrames = 0;
    uint64_t oldestBufferedFrame = 0;
    uint64_t latestProcessedFrame = 0;
    uint64_t latestCapturedFrame = 0;
    uint64_t latestHistoryFrame = 0;
    uint64_t featureCount = 0;
    int64_t latestFeatureCenterFrame = 0;
    double featureRateHz = 0.0;
    uint64_t skippedGridDeadlines = 0;
    size_t backlogFrames = 0;
    uint64_t matcherRunCount = 0;
    int64_t lastMatcherIntervalFrames = 0;
    uint64_t lastMatcherComputeMicros = 0;
    uint64_t historyLookupMisses = 0;
    uint64_t historyOverflowEvents = 0;
    uint64_t continuityResets = 0;
};

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
    // Independent atomic values; this is an approximate diagnostic snapshot,
    // not a coherent cross-counter transaction. Never reads analyzer-owned data.
    AudioAnalyzerDiagnostics diagnostics() const noexcept {
        AudioAnalyzerDiagnostics d;
        d.sampleRate = diagnosticSampleRate_.load(std::memory_order_relaxed);
        d.streamEpoch = transport_ ? transport_->streamEpoch()
                                   : diagnosticStreamEpoch_.load(std::memory_order_relaxed);
        d.framesConsumed = framesConsumed_.load(std::memory_order_relaxed);
        d.capturedFrames = transport_ ? transport_->capturedFrameWatermark()
                                     : diagnosticCaptureFrame_.load(std::memory_order_relaxed);
        d.writtenFrames = ring_.writtenSamples();
        d.consumedFrames = ring_.consumedSamples();
        d.droppedFrames = ring_.droppedSamples();
        d.oldestBufferedFrame = diagnosticOldestBufferedFrame_.load(std::memory_order_relaxed);
        d.latestProcessedFrame = diagnosticProcessedFrame_.load(std::memory_order_relaxed);
        d.latestCapturedFrame = d.capturedFrames;
        d.latestHistoryFrame = diagnosticCaptureFrame_.load(std::memory_order_relaxed);
        d.featureCount = featureSequence_.load(std::memory_order_relaxed);
        d.latestFeatureCenterFrame = diagnosticFeatureCenter_.load(std::memory_order_relaxed);
        d.featureRateHz = diagnosticFeatureRateHz_.load(std::memory_order_relaxed);
        d.skippedGridDeadlines = skippedGridDeadlines_.load(std::memory_order_relaxed);
        d.backlogFrames = ring_.available();
        d.matcherRunCount = matcherRunCount_.load(std::memory_order_relaxed);
        d.lastMatcherIntervalFrames = lastMatcherIntervalFrames_.load(std::memory_order_relaxed);
        d.lastMatcherComputeMicros = lastMatcherComputeMicros_.load(std::memory_order_relaxed);
        d.historyLookupMisses = historyLookupMisses_.load(std::memory_order_relaxed);
        d.historyOverflowEvents = historyOverflowEvents_.load(std::memory_order_relaxed);
        d.continuityResets = continuityResets_.load(std::memory_order_relaxed);
        return d;
    }
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
    void resetDspForSpan(const AudioSourceSpan& span) noexcept;
    FeatureGrid featureGrid_;
    SpscAudioRing& ring_;
    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<uint64_t> framesConsumed_{0};
    std::atomic<uint64_t> featureSequence_{0};
    std::atomic<uint64_t> skippedGridDeadlines_{0};
    std::atomic<int64_t> diagnosticFeatureCenter_{0};
    std::atomic<uint64_t> diagnosticCaptureFrame_{0};
    std::atomic<uint64_t> diagnosticStreamEpoch_{0};
    std::atomic<uint64_t> diagnosticProcessedFrame_{0};
    std::atomic<uint64_t> diagnosticOldestBufferedFrame_{0};
    std::atomic<double> diagnosticFeatureRateHz_{0.0};
    std::atomic<int32_t> diagnosticSampleRate_{0};
    std::atomic<uint64_t> matcherRunCount_{0};
    std::atomic<int64_t> lastMatcherIntervalFrames_{0};
    std::atomic<uint64_t> lastMatcherComputeMicros_{0};
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
    FeatureMatcherCadence matcherCadence_;
    TransportHistory historyCopy_;
    std::atomic<uint64_t> appliedResetRequest_{0};
    size_t freshFeatureFrames_ = 0;
    uint64_t diagnosticFeatureCount_ = 0;
    int64_t diagnosticFirstFeatureCenter_ = 0;
    uint64_t activeStreamEpoch_ = 0;
    uint64_t activeContinuityEpoch_ = 0;
    uint64_t activePositionGeneration_ = 0;
    uint64_t stftSourceOriginFrame_ = 0;
    uint64_t expectedNextSourceFrame_ = 0;
    uint64_t observationSequence_ = 0;
    uint32_t historyOverflowSeen_ = 0;
    bool dspReady_ = false;
    std::atomic<uint64_t> historyLookupMisses_{0};
    std::atomic<uint64_t> historyOverflowEvents_{0};
    std::atomic<uint64_t> continuityResets_{0};
    int32_t sampleRate_ = 48000;
    double expectedBpm_ = 120.0;
};
}
