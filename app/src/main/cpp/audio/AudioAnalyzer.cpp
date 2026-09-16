#include "audio/AudioAnalyzer.h"
#include <chrono>
#include <system_error>
#include <memory>
#include <cmath>
namespace temposcore {
bool AudioAnalyzer::start(int32_t sampleRate, double expectedBpm) {
    if (running_.exchange(true, std::memory_order_acq_rel)) return false;
    framesConsumed_.store(0, std::memory_order_relaxed);
    nextGridCenter_ = 2048;
    featureRing_.clear();
    lastMatcherCenter_ = 0;
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
void AudioAnalyzer::run(int32_t /*sampleRate*/) noexcept {
    float buffer[1024];
    while (running_.load(std::memory_order_acquire)) {
        const size_t count = ring_.read(buffer, 1024);
        if (count) {
            framesConsumed_.fetch_add(count, std::memory_order_relaxed);
            if (stft_->process(buffer, count)) {
                const int64_t center = static_cast<int64_t>((stft_->frameIndex() - 1) * stft_->hop() + stft_->fftSize() / 2);
                const auto bands = flux_->process(stft_->magnitude());
                const BeatObservation tempo = beatTracker_.processFlux(bands, stft_->frameEnergy(), center);
                // Publish every estimator update, including invalid observations, so
                // silence/lost lock clears the RT target and enables fallback.
                if (transport_) transport_->submitTempoObservation(tempo);
                if (center < nextGridCenter_) continue;
                AudioFeatureFrame frame;
                chroma_->extract(stft_->magnitude(), frame.chroma);
                frame.onset = bands[0] + bands[1] + bands[2];
                frame.energy = stft_->frameEnergy();
                frame.valid = frame.energy > 0.0001f;
                frame.centerAudioFrame = center;
                latestFeature_ = frame; // only analyzer writes; readers use sequence protocol.
                featureSequence_.fetch_add(1, std::memory_order_release);
                nextGridCenter_ = center + stft_->sampleRate() / 10;
                // Slow-loop score following (analyzer thread, non-RT): accumulate the
                // 10 Hz feature frame and run the PositionMatcher on a ~2 s
                // feature-time (sample-frame) cadence, NOT a wall clock or UI timer.
                featureRing_.push(frame);
                if (matcher_ && transport_ &&
                    center - lastMatcherCenter_ >= 2 * static_cast<int64_t>(stft_->sampleRate())) {
                    const double predicted = transport_->snapshot().quarterBeatPosition;
                    const PositionObservation obs = matcher_->update(featureRing_, predicted);
                    // The feature ring holds up to 200 frames @ 10 Hz = 20 s of
                    // live context; report how much of it is valid (spec §28 #11).
                    transport_->submitPositionObservation(obs, static_cast<double>(featureRing_.size()) / 10.0);
                    lastMatcherCenter_ = center;
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
