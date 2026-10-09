#include "audio/AudioAnalyzer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <memory>
#include <system_error>

namespace temposcore {

bool AudioAnalyzer::start(int32_t sampleRate, double expectedBpm) {
    if (running_.exchange(true, std::memory_order_acq_rel)) return false;
    framesConsumed_.store(0, std::memory_order_relaxed);
    sampleRate_ = sampleRate;
    diagnosticSampleRate_.store(sampleRate, std::memory_order_relaxed);
    expectedBpm_ = expectedBpm;
    featureRing_.clear();
    featureRing_.setSampleRate(sampleRate);
    historyCopy_.clear();
    matcherCadence_.reset();
    appliedResetRequest_.store(resetRequest_.load(std::memory_order_acquire), std::memory_order_release);
    freshFeatureFrames_ = 0;
    diagnosticFeatureCount_ = 0;
    diagnosticFirstFeatureCenter_ = 0;
    diagnosticFeatureCenter_.store(0, std::memory_order_relaxed);
    diagnosticCaptureFrame_.store(0, std::memory_order_relaxed);
    diagnosticProcessedFrame_.store(0, std::memory_order_relaxed);
    diagnosticOldestBufferedFrame_.store(0, std::memory_order_relaxed);
    diagnosticFeatureRateHz_.store(0.0, std::memory_order_relaxed);
    skippedGridDeadlines_.store(0, std::memory_order_relaxed);
    matcherRunCount_.store(0, std::memory_order_relaxed);
    lastMatcherIntervalFrames_.store(0, std::memory_order_relaxed);
    lastMatcherComputeMicros_.store(0, std::memory_order_relaxed);
    historyLookupMisses_.store(0, std::memory_order_relaxed);
    historyOverflowEvents_.store(0, std::memory_order_relaxed);
    continuityResets_.store(0, std::memory_order_relaxed);
    diagnosticDetectedBpm_.store(0.0, std::memory_order_relaxed);
    diagnosticRawDetectedBpm_.store(0.0, std::memory_order_relaxed);
    diagnosticSelectedTempoLag_.store(0.0, std::memory_order_relaxed);
    diagnosticBeatConfidence_.store(0.0f, std::memory_order_relaxed);
    diagnosticDtwBestCost_.store(0.0, std::memory_order_relaxed);
    diagnosticDtwSecondCost_.store(0.0, std::memory_order_relaxed);
    diagnosticDtwBestQuarterBeat_.store(0.0, std::memory_order_relaxed);
    diagnosticDtwSecondQuarterBeat_.store(0.0, std::memory_order_relaxed);
    diagnosticDtwLiveFirstFrame_.store(0, std::memory_order_relaxed);
    diagnosticDtwLiveLastFrame_.store(0, std::memory_order_relaxed);
    diagnosticDtwValidFrameFraction_.store(0.0, std::memory_order_relaxed);
    activeStreamEpoch_ = 0;
    diagnosticStreamEpoch_.store(0, std::memory_order_relaxed);
    activeContinuityEpoch_ = 0;
    activePositionGeneration_ = 0;
    observationSequence_ = 0;
    dspReady_ = false;
    historyOverflowSeen_ = transport_ ? transport_->historyOverflowCount() : 0;
    stft_ = std::make_unique<Stft>(sampleRate);
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

void AudioAnalyzer::resetDspForSpan(const AudioSourceSpan& span) noexcept {
    stft_ = std::make_unique<Stft>(sampleRate_);
    chroma_ = std::make_unique<ChromaExtractor>(sampleRate_);
    flux_ = std::make_unique<SpectralFlux>(sampleRate_);
    beatTracker_.configure(expectedBpm_, sampleRate_, static_cast<int32_t>(stft_->hop()));
    stftSourceOriginFrame_ = span.firstFrame;
    expectedNextSourceFrame_ = span.firstFrame;
    activeStreamEpoch_ = span.streamEpoch;
    diagnosticStreamEpoch_.store(span.streamEpoch, std::memory_order_relaxed);
    activeContinuityEpoch_ = span.continuityEpoch;
    activePositionGeneration_ = span.positionGeneration;
    featureGrid_.reset(sampleRate_, static_cast<int64_t>(span.firstFrame + stft_->fftSize() / 2));
    dspReady_ = true;
}

void AudioAnalyzer::run(int32_t /*sampleRate*/) noexcept {
    float buffer[1024];
    while (running_.load(std::memory_order_acquire)) {
        const uint64_t reset = resetRequest_.load(std::memory_order_acquire);
        if (reset != appliedResetRequest_.load(std::memory_order_acquire)) {
            appliedResetRequest_.store(reset, std::memory_order_release);
            featureRing_.clear();
            historyCopy_.clear();
            matcherCadence_.reset();
            freshFeatureFrames_ = 0;
            diagnosticFeatureCount_ = 0;
            diagnosticFirstFeatureCenter_ = 0;
            diagnosticFeatureCenter_.store(0, std::memory_order_relaxed);
            diagnosticFeatureRateHz_.store(0.0, std::memory_order_relaxed);
            float discard[1024];
            AudioSourceSpan discardedSpan;
            while (ring_.readStamped(discard, 1024, discardedSpan) != 0) {}
            dspReady_ = false;
            if (matcher_) {
                if (resetLocal_.load(std::memory_order_relaxed)) matcher_->requestLocalReacquire();
                else matcher_->resetCadence();
            }
            if (transport_) transport_->invalidatePositionState();
        }

        if (transport_) {
            const uint32_t overflow = transport_->historyOverflowCount();
            if (overflow != historyOverflowSeen_) {
                historyOverflowSeen_ = overflow;
                historyCopy_.clear();
                featureRing_.clear();
                matcherCadence_.reset();
                freshFeatureFrames_ = 0;
                if (matcher_) matcher_->resetCadence();
                transport_->invalidatePositionState();
                historyOverflowEvents_.fetch_add(1, std::memory_order_relaxed);
            }
            TransportHistorySegment segment;
            while (transport_->popHistorySegment(segment)) {
                historyCopy_.append(segment);
                diagnosticCaptureFrame_.store(segment.sourceEndFrame(), std::memory_order_relaxed);
            }
        }

        const uint64_t batchEpoch = appliedResetRequest_.load(std::memory_order_acquire);
        AudioSourceSpan span;
        const size_t count = ring_.readStamped(buffer, 1024, span);
        if (resetRequest_.load(std::memory_order_acquire) != batchEpoch) continue;
        if (!count) {
            try {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            } catch (const std::system_error&) {
                // Stop flag remains authoritative.
            }
            continue;
        }
        framesConsumed_.fetch_add(count, std::memory_order_relaxed);
        diagnosticProcessedFrame_.store(span.firstFrame + span.frameCount, std::memory_order_relaxed);
        AudioSourceSpan upcomingSpan;
        diagnosticOldestBufferedFrame_.store(
            ring_.peekNextSourceSpan(upcomingSpan) ? upcomingSpan.firstFrame
                                                   : diagnosticCaptureFrame_.load(std::memory_order_relaxed),
            std::memory_order_relaxed);

        const bool discontinuity = !dspReady_ || span.streamEpoch != activeStreamEpoch_ ||
            span.continuityEpoch != activeContinuityEpoch_ ||
            span.positionGeneration != activePositionGeneration_ ||
            span.firstFrame != expectedNextSourceFrame_;
        if (discontinuity) {
            if (dspReady_) continuityResets_.fetch_add(1, std::memory_order_relaxed);
            featureRing_.clear();
            matcherCadence_.reset();
            freshFeatureFrames_ = 0;
            diagnosticFeatureCount_ = 0;
            diagnosticFirstFeatureCenter_ = 0;
            diagnosticFeatureRateHz_.store(0.0, std::memory_order_relaxed);
            if (matcher_) matcher_->resetCadence();
            resetDspForSpan(span);
            if (transport_) transport_->invalidatePositionState();
        }
        expectedNextSourceFrame_ = span.firstFrame + span.frameCount;

        if (!stft_->process(buffer, count)) continue;
        const int64_t localCenter = static_cast<int64_t>(
            (stft_->frameIndex() - 1) * stft_->hop() + stft_->fftSize() / 2);
        const uint64_t centerSourceFrame = stftSourceOriginFrame_ + static_cast<uint64_t>(localCenter);
        const auto bands = flux_->process(stft_->magnitude());
        const BeatObservation tempo = beatTracker_.processFlux(
            bands, stft_->frameEnergy(), static_cast<int64_t>(centerSourceFrame));
        diagnosticDetectedBpm_.store(tempo.detectedBpm, std::memory_order_relaxed);
        diagnosticRawDetectedBpm_.store(tempo.rawDetectedBpm, std::memory_order_relaxed);
        diagnosticSelectedTempoLag_.store(tempo.selectedLag, std::memory_order_relaxed);
        diagnosticBeatConfidence_.store(tempo.confidence, std::memory_order_relaxed);
        const bool currentAfterDsp = resetRequest_.load(std::memory_order_acquire) == batchEpoch &&
            (!transport_ || transport_->positionGeneration() == span.positionGeneration);
        if (transport_ && currentAfterDsp) transport_->submitTempoObservation(tempo);

        uint64_t skippedDeadlines = 0;
        if (!featureGrid_.due(static_cast<int64_t>(centerSourceFrame), &skippedDeadlines)) continue;
        if (skippedDeadlines) skippedGridDeadlines_.fetch_add(skippedDeadlines, std::memory_order_relaxed);

        AudioFeatureFrame frame;
        chroma_->extract(stft_->magnitude(), frame.chroma);
        frame.onset = bands[0] + bands[1] + bands[2];
        frame.energy = stft_->frameEnergy();
        frame.valid = frame.energy > 0.0001f;
        frame.centerAudioFrame = static_cast<int64_t>(centerSourceFrame);
        frame.streamEpoch = span.streamEpoch;
        frame.continuityEpoch = span.continuityEpoch;
        frame.positionGeneration = span.positionGeneration;
        if (!currentAfterDsp) {
            featureRing_.clear();
            matcherCadence_.reset();
            freshFeatureFrames_ = 0;
            if (matcher_) matcher_->resetCadence();
            if (transport_) transport_->invalidatePositionState();
            continue;
        }

        AudioSourceSpan featureStamp{frame.streamEpoch, frame.continuityEpoch,
                                     frame.positionGeneration, centerSourceFrame, 0};
        TransportHistoryPoint historicalPoint;
        if (transport_ && !historyCopy_.lookup(featureStamp, historicalPoint)) {
            featureRing_.clear();
            matcherCadence_.reset();
            freshFeatureFrames_ = 0;
            if (matcher_) matcher_->resetCadence();
            transport_->invalidatePositionState();
            historyLookupMisses_.fetch_add(1, std::memory_order_relaxed);
            continue;
        }

        latestFeature_ = frame; // Existing UI polling API; phase-2 matcher does not read this shared payload.
        diagnosticFeatureCenter_.store(centerSourceFrame, std::memory_order_relaxed);
        featureSequence_.fetch_add(1, std::memory_order_release);
        if (diagnosticFeatureCount_++ == 0) diagnosticFirstFeatureCenter_ = static_cast<int64_t>(centerSourceFrame);
        const int64_t featureSpan = static_cast<int64_t>(centerSourceFrame) - diagnosticFirstFeatureCenter_;
        if (featureSpan > 0) {
            diagnosticFeatureRateHz_.store(
                static_cast<double>(diagnosticFeatureCount_ - 1) * sampleRate_ / featureSpan,
                std::memory_order_relaxed);
        }
        featureRing_.push(frame);
        ++freshFeatureFrames_;
        if (matcher_ && transport_ &&
            matcherCadence_.due(static_cast<int64_t>(centerSourceFrame), sampleRate_, freshFeatureFrames_)) {
            const int64_t matcherInterval = matcherCadence_.intervalFrames(static_cast<int64_t>(centerSourceFrame));
            const auto matcherStart = std::chrono::steady_clock::now();
            PositionObservation observation = matcher_->update(
                featureRing_, historicalPoint.position, span.positionGeneration);
            const auto matchDiagnostics = matcher_->diagnostics();
            diagnosticDtwBestCost_.store(matchDiagnostics.bestCost, std::memory_order_relaxed);
            diagnosticDtwSecondCost_.store(matchDiagnostics.secondCost, std::memory_order_relaxed);
            diagnosticDtwBestQuarterBeat_.store(matchDiagnostics.bestQuarterBeat, std::memory_order_relaxed);
            diagnosticDtwSecondQuarterBeat_.store(matchDiagnostics.secondQuarterBeat, std::memory_order_relaxed);
            diagnosticDtwLiveFirstFrame_.store(matchDiagnostics.liveFirstFrame, std::memory_order_relaxed);
            diagnosticDtwLiveLastFrame_.store(matchDiagnostics.liveLastFrame, std::memory_order_relaxed);
            diagnosticDtwValidFrameFraction_.store(matchDiagnostics.validFrameFraction, std::memory_order_relaxed);
            observation.streamEpoch = frame.streamEpoch;
            observation.continuityEpoch = frame.continuityEpoch;
            observation.observationFrame = centerSourceFrame;
            observation.sequence = ++observationSequence_;
            const auto matcherElapsed = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - matcherStart).count();
            if (resetRequest_.load(std::memory_order_acquire) == batchEpoch &&
                transport_->positionGeneration() == span.positionGeneration) {
                transport_->submitPositionObservation(observation, featureRing_.durationSeconds());
            }
            lastMatcherIntervalFrames_.store(matcherInterval, std::memory_order_relaxed);
            lastMatcherComputeMicros_.store(static_cast<uint64_t>(std::max<int64_t>(0, matcherElapsed)),
                                            std::memory_order_relaxed);
            matcherRunCount_.fetch_add(1, std::memory_order_relaxed);
            matcherCadence_.markRun(static_cast<int64_t>(centerSourceFrame));
        }
    }
}

bool AudioAnalyzer::latestFeature(AudioFeatureFrame& out) const noexcept {
    const uint64_t before = featureSequence_.load(std::memory_order_acquire);
    if (!before) return false;
    out = latestFeature_;
    return before == featureSequence_.load(std::memory_order_acquire);
}

} // namespace temposcore
