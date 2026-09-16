#pragma once

#include "audio/AudioAnalyzer.h"
#include "position/PositionMatcher.h"
#include "position/ScoreReference.h"
#include "transport/LiveTransport.h"

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>

namespace temposcore {

class OboeInputEngine final : public oboe::AudioStreamDataCallback,
                              public oboe::AudioStreamErrorCallback {
public:
    void initialize(double expectedBpm, double startQuarterBeat) noexcept;
    bool start();
    void stop();
    void setExpectedBpm(double bpm) noexcept;
    void resetPosition(double startQuarterBeat) noexcept;
    // Build the score reference from the parsed MIDI and (re)create the
    // PositionMatcher. Called on the main thread (JNI) BEFORE start().
    void setScoreReference(const MidiData& data) noexcept;
    // Request a global re-acquisition (main thread, JNI). The analyzer thread
    // applies it on its next update() — no cross-thread state write.
    void requestGlobalReacquire() noexcept { if (matcher_) matcher_->requestReacquire(); }
    TransportState state() const noexcept;
    PositionTrackingState trackingState() const noexcept {
        return matcher_ ? matcher_->state() : PositionTrackingState::Idle;
    }
    uint64_t framesConsumed() const noexcept { return analyzer_.framesConsumed(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream,
                                          void* audioData,
                                          int32_t numFrames) override;
    void onErrorBeforeClose(oboe::AudioStream* audioStream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result error) override;

private:
    bool openStream(oboe::InputPreset preset);

    std::shared_ptr<oboe::AudioStream> stream_;
    LiveTransport transport_;
    SpscAudioRing ring_{200000}; // ~4.1 s mono at 48 kHz; allocated before start.
    AudioAnalyzer analyzer_{ring_};
    ScoreReference reference_;
    std::unique_ptr<PositionMatcher> matcher_;
    double expectedBpm_ = 120.0;
    int32_t sampleRate_ = 48000;
    int32_t channelCount_ = 1;
    std::atomic<bool> streamError_{false};
};

} // namespace temposcore
