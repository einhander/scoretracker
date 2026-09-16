#include "audio/OboeInputEngine.h"

#include <algorithm>
#include <cmath>

namespace temposcore {

void OboeInputEngine::initialize(double expectedBpm, double startQuarterBeat) noexcept {
    if (!std::isfinite(expectedBpm)) expectedBpm = 120.0;
    expectedBpm_ = std::clamp(expectedBpm, 30.0, 300.0);
    transport_.configure(expectedBpm_, startQuarterBeat);
}

bool OboeInputEngine::openStream(oboe::InputPreset preset) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(1)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setInputPreset(preset)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK || !stream_) {
        stream_.reset();
        return false;
    }

    sampleRate_ = stream_->getSampleRate();
    channelCount_ = std::max(1, stream_->getChannelCount());
    return true;
}

bool OboeInputEngine::start() {
    stop();
    streamError_.store(false, std::memory_order_relaxed);

    // UNPROCESSED is preferable for music analysis when a device exposes it. Some Android
    // devices reject it, so fall back to VoiceRecognition, which Oboe documents as a common
    // low-latency default for input.
    if (!openStream(oboe::InputPreset::Unprocessed)) {
        if (!openStream(oboe::InputPreset::VoiceRecognition)) return false;
    }

    ring_.reset();
    if (matcher_) matcher_->begin(); // fresh acquisition on each start
    if (!analyzer_.start(sampleRate_, expectedBpm_)) return false;
    const oboe::Result result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        stream_->close();
        stream_.reset();
        analyzer_.stop();
        return false;
    }

    transport_.setRunning(true);
    return true;
}

bool OboeInputEngine::startTest(int32_t sampleRate) {
    stop();
    if (sampleRate < 8000 || sampleRate > 192000) return false;

    sampleRate_ = sampleRate;
    channelCount_ = 1;
    streamError_.store(false, std::memory_order_relaxed);
    ring_.reset();
    if (matcher_) matcher_->begin();
    if (!analyzer_.start(sampleRate_, expectedBpm_)) return false;
    transport_.setRunning(true);
    return true;
}

void OboeInputEngine::pushTestAudio(const float* mono, size_t numFrames) noexcept {
    if (mono == nullptr || numFrames == 0 || !transport_.snapshot().running) return;
    // Test PCM is paced by AudioTrack on the Kotlin side. Advance the exact same
    // transport clock and feed the exact same analyzer ring as the Oboe callback.
    transport_.processFrames(static_cast<int32_t>(numFrames), sampleRate_);
    ring_.write(mono, numFrames);
}

void OboeInputEngine::stop() {
    // Join the analyzer FIRST: no further submitPositionObservation can land
    // after this, so the Idle state published below is the final one (gate-5 m8).
    analyzer_.stop();
    transport_.setRunning(false);
    transport_.publishIdle(); // spec §30: Stop -> tracking state Idle
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

void OboeInputEngine::setExpectedBpm(double bpm) noexcept {
    if (!std::isfinite(bpm)) return;
    expectedBpm_ = std::clamp(bpm, 30.0, 300.0);
    transport_.setExpectedBpm(expectedBpm_);
    // Non-resetting: safe during tempo-region crossings; analyzer preserves
    // accumulated flux history while only updating its MIDI tempo prior.
    analyzer_.setExpectedBpm(expectedBpm_);
}

void OboeInputEngine::resetPosition(double startQuarterBeat) noexcept {
    transport_.resetPosition(startQuarterBeat);
}

void OboeInputEngine::setManualPosition(double startQuarterBeat) noexcept {
    transport_.resetPosition(startQuarterBeat);
    if (matcher_) matcher_->requestLocalReacquire();
}

void OboeInputEngine::setScoreReference(const MidiData& data) noexcept {
    buildScoreReference(data, reference_);
    matcher_.reset(new PositionMatcher(reference_));
    // Wire the slow-loop follower into the analyzer (main thread, before start).
    analyzer_.configureMatcher(matcher_.get(), &transport_);
}

TransportState OboeInputEngine::state() const noexcept {
    return transport_.snapshot();
}

oboe::DataCallbackResult OboeInputEngine::onAudioReady(oboe::AudioStream* /*audioStream*/,
                                                        void* audioData,
                                                        int32_t numFrames) {
    const auto* input = static_cast<const float*>(audioData);
    transport_.processFrames(numFrames, sampleRate_);
    // Stream is configured mono, so input is contiguous mono float samples.
    ring_.write(input, static_cast<size_t>(numFrames));
    return oboe::DataCallbackResult::Continue;
}

void OboeInputEngine::onErrorBeforeClose(oboe::AudioStream* /*audioStream*/, oboe::Result /*error*/) {
    streamError_.store(true, std::memory_order_relaxed);
    transport_.setRunning(false);
}

void OboeInputEngine::onErrorAfterClose(oboe::AudioStream* /*audioStream*/, oboe::Result /*error*/) {
    streamError_.store(true, std::memory_order_relaxed);
    transport_.setRunning(false);
}

} // namespace temposcore
