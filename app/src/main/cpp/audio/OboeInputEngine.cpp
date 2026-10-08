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
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
    testPcmWriterGate_.closeAndWait();
    activeTestSession_.store(0, std::memory_order_release);
    activeTestCallerSession_.store(0, std::memory_order_release);
    activeTestEngineToken_ = 0;
    stopInputLocked();
    streamError_.store(false, std::memory_order_relaxed);

    // UNPROCESSED is preferable for music analysis when a device exposes it. Some Android
    // devices reject it, so fall back to VoiceRecognition, which Oboe documents as a common
    // low-latency default for input.
    if (!openStream(oboe::InputPreset::Unprocessed)) {
        if (!openStream(oboe::InputPreset::VoiceRecognition)) return false;
    }

    ring_.reset();
    ++streamEpoch_;
    if (!streamEpoch_) ++streamEpoch_;
    capturedFrames_ = 0;
    continuityEpoch_ = 1;
    transport_.beginStreamEpoch(streamEpoch_);
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

uint64_t OboeInputEngine::startTest(int32_t sampleRate, uint64_t callerSession) {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
    if (sampleRate < 8000 || sampleRate > 192000 ||
        !testPcmSessionIdentity_.begin(callerSession)) return 0;
    activeTestCallerSession_.store(callerSession, std::memory_order_release);
    if (!testPcmSessionIdentity_.current(callerSession)) {
        uint64_t expected = callerSession;
        activeTestCallerSession_.compare_exchange_strong(expected, 0, std::memory_order_acq_rel,
                                                         std::memory_order_acquire);
        return 0;
    }
    testPcmWriterGate_.closeAndWait();
    activeTestSession_.store(0, std::memory_order_release);
    activeTestEngineToken_ = 0;
    stopInputLocked();
    if (activeTestCallerSession_.load(std::memory_order_acquire) != callerSession ||
        !testPcmSessionIdentity_.current(callerSession)) {
        uint64_t expected = callerSession;
        activeTestCallerSession_.compare_exchange_strong(expected, 0, std::memory_order_acq_rel,
                                                         std::memory_order_acquire);
        return 0;
    }

    sampleRate_ = sampleRate;
    channelCount_ = 1;
    streamError_.store(false, std::memory_order_relaxed);
    ring_.reset();
    ++streamEpoch_;
    if (!streamEpoch_) ++streamEpoch_;
    capturedFrames_ = 0;
    continuityEpoch_ = 1;
    transport_.beginStreamEpoch(streamEpoch_);
    // Preserve test seek/restart contract: reacquire from score origin, never
    // infer score position from decoder PTS.
    transport_.resetPosition(0.0);
    if (matcher_) matcher_->begin();
    if (!analyzer_.start(sampleRate_, expectedBpm_)) {
        uint64_t expected = callerSession;
        activeTestCallerSession_.compare_exchange_strong(expected, 0, std::memory_order_acq_rel,
                                                         std::memory_order_acquire);
        return 0;
    }
    if (activeTestCallerSession_.load(std::memory_order_acquire) != callerSession ||
        !testPcmSessionIdentity_.current(callerSession)) {
        analyzer_.stop();
        uint64_t expected = callerSession;
        activeTestCallerSession_.compare_exchange_strong(expected, 0, std::memory_order_acq_rel,
                                                         std::memory_order_acquire);
        return 0;
    }
    const uint64_t nativeGateSession = testPcmWriterGate_.openNextSession();
    if (!nativeGateSession || !testPcmWriterGate_.activateSession(nativeGateSession)) {
        analyzer_.stop();
        uint64_t expected = callerSession;
        activeTestCallerSession_.compare_exchange_strong(expected, 0, std::memory_order_acq_rel,
                                                         std::memory_order_acquire);
        return 0;
    }
    activeTestSession_.store(nativeGateSession, std::memory_order_release);
    transport_.setRunning(true);
    if (activeTestCallerSession_.load(std::memory_order_acquire) != callerSession ||
        !testPcmSessionIdentity_.current(callerSession) ||
        !testPcmWriterGate_.isActiveSession(nativeGateSession)) {
        uint64_t expected = nativeGateSession;
        activeTestSession_.compare_exchange_strong(expected, 0, std::memory_order_acq_rel,
                                                   std::memory_order_acquire);
        testPcmWriterGate_.closeSessionAndWait(nativeGateSession);
        transport_.setRunning(false);
        analyzer_.stop();
        uint64_t expectedCaller = callerSession;
        activeTestCallerSession_.compare_exchange_strong(expectedCaller, 0, std::memory_order_acq_rel,
                                                         std::memory_order_acquire);
        return 0;
    }
    activeTestEngineToken_ = callerSession;
    return callerSession;
}

void OboeInputEngine::revokeTestSession(uint64_t session) noexcept {
    testPcmSessionIdentity_.revoke(session);
    if (!session) return;
    revokeSessionIfCurrent(lifecycleMutex_, activeTestCallerSession_, session, [&]() noexcept {
        const uint64_t nativeGateSession = activeTestSession_.exchange(0, std::memory_order_acq_rel);
        if (nativeGateSession) testPcmWriterGate_.closeSession(nativeGateSession);
        transport_.setRunning(false);
        transport_.publishIdle();
    });
}

void OboeInputEngine::stopTest(uint64_t session) {
    if (!session) return;
    revokeTestSession(session);
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
    if (!testPcmSessionIdentity_.latestStarted(session)) return;
    if (activeTestEngineToken_ != session) return;
    const uint64_t nativeGateSession = activeTestSession_.exchange(0, std::memory_order_acq_rel);
    if (nativeGateSession) testPcmWriterGate_.closeSessionAndWait(nativeGateSession);
    if (activeTestCallerSession_.load(std::memory_order_acquire) != 0) return;
    activeTestEngineToken_ = 0;
    stopInputLocked();
}

bool OboeInputEngine::testSessionActive(uint64_t sessionToken) const noexcept {
    const uint64_t nativeGateSession = activeTestSession_.load(std::memory_order_acquire);
    return sessionToken != 0 && activeTestCallerSession_.load(std::memory_order_acquire) == sessionToken &&
           testPcmSessionIdentity_.current(sessionToken) &&
           nativeGateSession != 0 && testPcmWriterGate_.isActiveSession(nativeGateSession);
}

void OboeInputEngine::pushTestAudio(uint64_t session, const float* mono, size_t numFrames) noexcept {
    if (!testSessionActive(session) || mono == nullptr || numFrames == 0 ||
        !transport_.snapshot().running) return;
    std::lock_guard<std::mutex> writerLock(testPcmWriterMutex_);
    if (activeTestCallerSession_.load(std::memory_order_acquire) != session) return;
    const uint64_t nativeGateSession = activeTestSession_.load(std::memory_order_acquire);
    testPcmSessionIdentity_.runIfCurrent(session, testPcmWriterGate_, nativeGateSession, [&]() noexcept {
        const uint64_t firstFrame = capturedFrames_;
        capturedFrames_ += numFrames;
        const uint64_t positionGeneration = transport_.processSourceFrames(
            static_cast<int32_t>(numFrames), sampleRate_, streamEpoch_, continuityEpoch_, firstFrame);
        const AudioSourceSpan span{streamEpoch_, continuityEpoch_, positionGeneration,
                                   firstFrame, static_cast<uint32_t>(numFrames)};
        const size_t written = ring_.writeStamped(mono, numFrames, span);
        if (written != numFrames) ++continuityEpoch_;
    });
}

void OboeInputEngine::stopInputLocked() {
    activeTestEngineToken_ = 0;
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
    // Quiesce capture before joining worker, then publish Idle as final state.
    analyzer_.stop();
    transport_.setRunning(false);
    transport_.publishIdle(); // spec §30: Stop -> tracking state Idle
}

void OboeInputEngine::stop() {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
    testPcmWriterGate_.closeAndWait();
    activeTestSession_.store(0, std::memory_order_release);
    activeTestCallerSession_.store(0, std::memory_order_release);
    activeTestEngineToken_ = 0;
    stopInputLocked();
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
    analyzer_.requestPositionReset(false);
    transport_.resetPosition(startQuarterBeat);
}

void OboeInputEngine::setManualPosition(double startQuarterBeat) noexcept {
    analyzer_.requestPositionReset(true);
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
    if (numFrames <= 0) return oboe::DataCallbackResult::Continue;
    const auto* input = static_cast<const float*>(audioData);
    const uint64_t firstFrame = capturedFrames_;
    capturedFrames_ += static_cast<uint64_t>(numFrames);
    const uint64_t positionGeneration = transport_.processSourceFrames(
        numFrames, sampleRate_, streamEpoch_, continuityEpoch_, firstFrame);
    // Stream is configured mono. Stamp captured range before drop-incoming write.
    const AudioSourceSpan span{streamEpoch_, continuityEpoch_, positionGeneration,
                               firstFrame, static_cast<uint32_t>(numFrames)};
    const size_t written = ring_.writeStamped(input, static_cast<size_t>(numFrames), span);
    if (written != static_cast<size_t>(numFrames)) ++continuityEpoch_;
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
