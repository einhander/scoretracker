#include "audio/AudioAnalyzer.h"
#include <algorithm>
#include <chrono>
#include <system_error>
#include <memory>
#include <cmath>
namespace temposcore {
bool AudioAnalyzer::start(int32_t sampleRate, double expectedBpm) {
    if (running_.exchange(true, std::memory_order_acq_rel)) return false;
    framesConsumed_.store(0, std::memory_order_relaxed);
    sampleRate_ = sampleRate;
    diagnosticSampleRate_.store(sampleRate, std::memory_order_relaxed);
    expectedBpm_ = expectedBpm;
    featureRing_.clear();
    featureRing_.setSampleRate(sampleRate);
    matcherCadence_.reset();
    appliedResetRequest_.store(resetRequest_.load(std::memory_order_acquire), std::memory_order_release);
    freshFeatureFrames_ = 0;
    diagnosticFeatureCount_ = 0;
    diagnosticFirstFeatureCenter_ = 0;
    diagnosticFeatureCenter_.store(0, std::memory_order_relaxed);
    diagnosticFeatureRateHz_.store(0.0, std::memory_order_relaxed);
    skippedGridDeadlines_.store(0, std::memory_order_relaxed);
    matcherRunCount_.store(0, std::memory_order_relaxed);
    lastMatcherIntervalFrames_.store(0, std::memory_order_relaxed);
    lastMatcherComputeMicros_.store(0, std::memory_order_relaxed);
    stft_ = std::make_unique<Stft>(sampleRate);
    featureGrid_.reset(sampleRate, static_cast<int64_t>(stft_->fftSize() / 2));
    chroma_ = std::make_unique<ChromaExtractor>(sampleRate);
    flux_ = std::make_unique<SpectralFlux>(sampleRate);
    beatTracker_.configure(expectedBpm, sampleRate, static_cast<int32_t>(stft_->hop()));
    worker_ = std::thread(&AudioAnalyzer::run, this, sampleRate);
    return true;
}
void AudioAnalyzer::stop() noexcept {
    if (!running_.exchange(false, std::memory_order_acq_rel)) return;
    if (worker_.joinable()) worker_.join();
}
void AudioAnalyzer::run(int32_t /*sampleRate*/) noexcept {
    float buffer[1024];
    while (running_.load(std::memory_order_acquire)) {
        const uint64_t reset = resetRequest_.load(std::memory_order_acquire);
        if (reset != appliedResetRequest_.load(std::memory_order_acquire)) {
            appliedResetRequest_.store(reset, std::memory_order_release);
            featureRing_.clear();
            matcherCadence_.reset();
            freshFeatureFrames_ = 0;
            diagnosticFeatureCount_ = 0;
            diagnosticFirstFeatureCenter_ = 0;
            diagnosticFeatureCenter_.store(0, std::memory_order_relaxed);
            diagnosticFeatureRateHz_.store(0.0, std::memory_order_relaxed);
            float discard[1024];
            while (ring_.read(discard, 1024) != 0) {}
            stft_ = std::make_unique<Stft>(sampleRate_);
            featureGrid_.reset(sampleRate_, static_cast<int64_t>(stft_->fftSize() / 2));
            chroma_ = std::make_unique<ChromaExtractor>(sampleRate_);
            flux_ = std::make_unique<SpectralFlux>(sampleRate_);
            featureRing_.setSampleRate(sampleRate_);
            beatTracker_.configure(expectedBpm_, sampleRate_,
                                   static_cast<int32_t>(stft_->hop()));
            if (matcher_) {
                if (resetLocal_.load(std::memory_order_relaxed)) matcher_->requestLocalReacquire();
                else matcher_->resetCadence();
            }
        }
        const uint64_t batchEpoch = appliedResetRequest_.load(std::memory_order_acquire);
        const uint64_t batchGeneration = transport_ ? transport_->positionGeneration() : 0;
        const size_t count = ring_.read(buffer, 1024);
        // Reset may arrive after read and during processing. Such batch may
        // mutate private DSP state, but must never escape into any published
        // state, FeatureRing, warmup, cadence, or matcher result. Next loop
        // applies reset and recreates DSP before accepting new PCM.
        const auto batchStillCurrent = [&]() noexcept {
            return resetRequest_.load(std::memory_order_acquire) == batchEpoch &&
                   appliedResetRequest_.load(std::memory_order_acquire) == batchEpoch;
        };
        if (!batchStillCurrent()) continue;
        if (count) {
            framesConsumed_.fetch_add(count, std::memory_order_relaxed);
            if (stft_->process(buffer, count)) {
                const int64_t center = static_cast<int64_t>((stft_->frameIndex() - 1) * stft_->hop() + stft_->fftSize() / 2);
                const auto bands = flux_->process(stft_->magnitude());
                const BeatObservation tempo = beatTracker_.processFlux(bands, stft_->frameEnergy(), center);
                const bool currentAfterDsp = batchStillCurrent();
                // Publish every estimator update, including invalid observations, so
                // silence/lost lock clears the RT target and enables fallback.
                if (transport_ && currentAfterDsp) transport_->submitTempoObservation(tempo);
                uint64_t skippedDeadlines = 0;
                if (!featureGrid_.due(center, &skippedDeadlines)) continue;
                if (skippedDeadlines) skippedGridDeadlines_.fetch_add(skippedDeadlines, std::memory_order_relaxed);
                AudioFeatureFrame frame;
                chroma_->extract(stft_->magnitude(), frame.chroma);
                frame.onset = bands[0] + bands[1] + bands[2];
                frame.energy = stft_->frameEnergy();
                frame.valid = frame.energy > 0.0001f;
                frame.centerAudioFrame = center;
                if (!currentAfterDsp || !batchStillCurrent() ||
                    (transport_ && transport_->positionGeneration() != batchGeneration)) continue;
                latestFeature_ = frame; // only analyzer writes; readers use sequence protocol.
                diagnosticFeatureCenter_.store(center, std::memory_order_relaxed);
                featureSequence_.fetch_add(1, std::memory_order_release);
                if (diagnosticFeatureCount_++ == 0) diagnosticFirstFeatureCenter_ = center;
                const int64_t featureSpan = center - diagnosticFirstFeatureCenter_;
                if (featureSpan > 0) {
                    diagnosticFeatureRateHz_.store(
                        static_cast<double>(diagnosticFeatureCount_ - 1) * sampleRate_ / featureSpan,
                        std::memory_order_relaxed);
                }
                // Slow-loop score following (analyzer thread, non-RT): accumulate the
                // 10 Hz feature frame and run the PositionMatcher on a ~2 s
                // feature-time (sample-frame) cadence, NOT a wall clock or UI timer.
                featureRing_.push(frame);
                ++freshFeatureFrames_;
                if (matcher_ && transport_ && matcherCadence_.due(center, stft_->sampleRate(), freshFeatureFrames_)) {
                    const double predicted = transport_->snapshot().quarterBeatPosition;
                    const int64_t matcherInterval = matcherCadence_.intervalFrames(center);
                    const auto matcherStart = std::chrono::steady_clock::now();
                    const PositionObservation obs = matcher_->update(featureRing_, predicted, batchGeneration);
                    const auto matcherElapsed = std::chrono::duration_cast<std::chrono::microseconds>(
                        std::chrono::steady_clock::now() - matcherStart).count();
                    // The feature ring holds up to 200 frames @ 10 Hz = 20 s of
                    // live context; report how much of it is valid (spec §28 #11).
                    if (batchStillCurrent() && transport_->positionGeneration() == batchGeneration)
                        transport_->submitPositionObservation(obs, featureRing_.durationSeconds());
                    lastMatcherIntervalFrames_.store(matcherInterval, std::memory_order_relaxed);
                    lastMatcherComputeMicros_.store(static_cast<uint64_t>(std::max<int64_t>(0, matcherElapsed)),
                                                    std::memory_order_relaxed);
                    matcherRunCount_.fetch_add(1, std::memory_order_relaxed);
                    matcherCadence_.markRun(center);
                }
            }
        }
        else {
            try {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            } catch (const std::system_error&) {
                // Keep looping; stop signal remains authoritative.
            }
        }
    }
}
bool AudioAnalyzer::latestFeature(AudioFeatureFrame& out) const noexcept {
    const uint64_t before = featureSequence_.load(std::memory_order_acquire);
    if (!before) return false;
    out = latestFeature_;
    return before == featureSequence_.load(std::memory_order_acquire);
}
}
