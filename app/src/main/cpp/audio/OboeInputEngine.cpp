#include "audio/OboeInputEngine.h"

#include <algorithm>

namespace temposcore {

void OboeInputEngine::initialize(double expectedBpm, double startQuarterBeat) noexcept {
    expectedBpm_ = std::clamp(expectedBpm, 30.0, 300.0);
    transport_.configure(expectedBpm_, startQuarterBeat);
    beatTracker_.configure(expectedBpm_, sampleRate_);
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
    beatTracker_.configure(expectedBpm_, sampleRate_);
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
    if (!analyzer_.start(sampleRate_)) return false;
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

void OboeInputEngine::stop() {
    transport_.setRunning(false);
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
    analyzer_.stop();
}

void OboeInputEngine::setExpectedBpm(double bpm) noexcept {
    expectedBpm_ = std::clamp(bpm, 30.0, 300.0);
    transport_.setExpectedBpm(expectedBpm_);
    beatTracker_.configure(expectedBpm_, sampleRate_);
}

void OboeInputEngine::resetPosition(double startQuarterBeat) noexcept {
    transport_.resetPosition(startQuarterBeat);
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
    const BeatObservation observation = beatTracker_.process(input, numFrames, channelCount_);
    transport_.processFrames(numFrames, sampleRate_, observation);
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
