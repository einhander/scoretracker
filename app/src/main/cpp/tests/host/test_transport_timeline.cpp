#include "transport/LiveTransport.h"
#include "audio/AudioAnalyzer.h"
#include "audio/SpscAudioRing.h"
#include "tests/host/test_main.h"

#include <cmath>

using namespace temposcore;

namespace {
struct TransportRig {
    LiveTransport transport;
    TransportHistory workerHistory;
    uint64_t sourceFrame = 0;
    uint64_t generation = 0;
    TransportDiagnosticRecord lastDiagnostic{};

    TransportRig() {
        transport.configure(123.0, 0.0);
        transport.beginStreamEpoch(77);
        transport.enableDiagnostics(true);
    }

    void tick(uint32_t frames = 480, uint64_t continuity = 1) {
        transport.processSourceFrames(static_cast<int32_t>(frames), 48000, 77, continuity, sourceFrame);
        sourceFrame += frames;
        generation = transport.positionGeneration();
        TransportHistorySegment segment;
        while (transport.popHistorySegment(segment)) workerHistory.append(segment);
        while (transport.popDiagnostic(lastDiagnostic)) {}
    }

    TransportHistoryPoint at(uint64_t frame, uint64_t continuity = 1) const {
        AudioSourceSpan stamp{77, continuity, generation, frame, 0};
        TransportHistoryPoint point;
        CHECK(workerHistory.lookup(stamp, point));
        return point;
    }

    void submit(uint64_t frame, double offset, uint64_t sequence,
                uint64_t continuity = 1, float confidence = 0.95f) {
        const auto point = at(frame, continuity);
        PositionObservation observation;
        observation.quarterBeatPosition = point.position + offset;
        observation.confidence = confidence;
        observation.ambiguityMargin = 0.25f;
        observation.valid = true;
        observation.state = PositionTrackingState::Locked;
        observation.resetGeneration = generation;
        observation.streamEpoch = 77;
        observation.continuityEpoch = continuity;
        observation.observationFrame = frame;
        observation.sequence = sequence;
        transport.submitPositionObservation(observation, 20.0);
    }
};
}

void test_transport_observation_age_uses_historical_integrated_base() {
    for (const uint32_t delayFrames : {2400U, 12000U, 48000U, 96000U}) {
        TransportRig rig;
        for (int i = 0; i < 100; ++i) rig.tick();
        const uint64_t observationFrame = rig.sourceFrame;
        const auto truthAtObservation = rig.at(observationFrame);
        const uint32_t delayTicks = delayFrames / 480;
        for (uint32_t i = 0; i < delayTicks; ++i) {
            if (i == delayTicks / 2) {
                BeatObservation tempo;
                tempo.detectedBpm = 151.0;
                tempo.confidence = 0.96f;
                tempo.rms = 0.2f;
                tempo.tempoValid = true;
                rig.transport.submitTempoObservation(tempo);
            }
            rig.tick();
        }
        rig.submit(observationFrame, 0.0, 1);
        rig.tick();
        CHECK(rig.lastDiagnostic.rejectionCode == 0);
        CHECK(rig.lastDiagnostic.observationAgeFrames == delayFrames);
        CHECK(std::abs(rig.lastDiagnostic.rawObservationError) < 1e-8);
        CHECK(std::abs(rig.lastDiagnostic.projectedCorrectionError) < 1e-7);
        CHECK(std::abs(rig.lastDiagnostic.correctionDeltaBeats) < 1e-12);
        // Same source-time match remains unbiased through a BPM change during latency.
        CHECK(std::abs(rig.transport.snapshot().quarterBeatPosition -
                       (truthAtObservation.position +
                        (rig.at(rig.sourceFrame).baseBeats - truthAtObservation.baseBeats))) < 1e-6);
    }
}

void test_transport_rejects_stale_wrong_epoch_and_duplicate_observations() {
    TransportRig rig;
    for (int i = 0; i < 100; ++i) rig.tick();
    const uint64_t frame = rig.sourceFrame;
    for (int i = 0; i < 201; ++i) rig.tick();
    rig.submit(frame, 0.5, 1);
    rig.tick();
    CHECK(rig.lastDiagnostic.rejectionCode == 3); // >2 s at callback application boundary.
    CHECK(std::abs(rig.lastDiagnostic.correctionDeltaBeats) < 1e-12);

    TransportRig wrongEpoch;
    for (int i = 0; i < 10; ++i) wrongEpoch.tick();
    const auto wrongEpochPoint = wrongEpoch.at(wrongEpoch.sourceFrame);
    PositionObservation wrongEpochObservation;
    wrongEpochObservation.quarterBeatPosition = wrongEpochPoint.position + 1.0;
    wrongEpochObservation.confidence = 0.95f;
    wrongEpochObservation.ambiguityMargin = 0.25f;
    wrongEpochObservation.valid = true;
    wrongEpochObservation.resetGeneration = wrongEpoch.generation;
    wrongEpochObservation.streamEpoch = 77;
    wrongEpochObservation.continuityEpoch = 9;
    wrongEpochObservation.observationFrame = wrongEpoch.sourceFrame;
    wrongEpochObservation.sequence = 1;
    wrongEpoch.transport.submitPositionObservation(wrongEpochObservation, 2.0);
    wrongEpoch.tick(480, 1);
    CHECK(wrongEpoch.lastDiagnostic.rejectionCode == 1);
    CHECK(std::abs(wrongEpoch.lastDiagnostic.correctionDeltaBeats) < 1e-12);

    TransportRig duplicate;
    for (int i = 0; i < 10; ++i) duplicate.tick();
    const uint64_t duplicateFrame = duplicate.sourceFrame;
    duplicate.submit(duplicateFrame, 0.3, 1);
    duplicate.tick();
    duplicate.submit(duplicateFrame, 0.3, 1);
    duplicate.tick();
    CHECK(duplicate.lastDiagnostic.rejectionCode == 5);
}

void test_delayed_match_projection_subtracts_correction_applied_after_match_frame() {
    TransportRig rig;
    for (int i = 0; i < 10; ++i) rig.tick();
    const uint64_t oldObservationFrame = rig.sourceFrame;
    const auto oldPoint = rig.at(oldObservationFrame);

    // A later observation is applied first, after oldObservationFrame. Its
    // bounded correction actually moves the transport before the older match arrives.
    rig.tick(2400);
    const uint64_t newerFrame = rig.sourceFrame;
    rig.submit(newerFrame, 0.8, 1);
    rig.tick(480);
    CHECK(rig.lastDiagnostic.projectedCorrectionError > 0.79);
    const double correctionAppliedSinceOldFrame = rig.lastDiagnostic.correctionDeltaBeats;
    CHECK(correctionAppliedSinceOldFrame > 0.0);

    const uint64_t deliveryFrame = rig.sourceFrame;
    const auto pointAtDelivery = rig.at(deliveryFrame);
    const double delayedMatch = oldPoint.position + 0.3;
    const double expectedProjected = delayedMatch +
        (pointAtDelivery.baseBeats - oldPoint.baseBeats) - rig.transport.snapshot().quarterBeatPosition;
    rig.submit(oldObservationFrame, 0.3, 2);
    rig.tick(480);
    CHECK(rig.lastDiagnostic.rejectionCode == 0);
    CHECK(rig.lastDiagnostic.observationAgeFrames == deliveryFrame - oldObservationFrame);
    CHECK_NEAR(rig.lastDiagnostic.rawObservationError, 0.3, 1e-8);
    CHECK_NEAR(rig.lastDiagnostic.projectedCorrectionError, expectedProjected, 1e-8);
    CHECK_NEAR(expectedProjected, 0.3 - correctionAppliedSinceOldFrame, 1e-6);
}

void test_transport_effective_rate_tracks_bounded_positive_and_negative_corrections() {
    TransportRig rig;
    for (int i = 0; i < 100; ++i) rig.tick();
    uint64_t frame = rig.sourceFrame;
    rig.submit(frame, 0.25, 1);
    rig.tick(); // correction starts at delivery boundary, never retroactively in earlier block.
    CHECK(rig.lastDiagnostic.correctionDeltaBeats > 0.0);
    CHECK(rig.lastDiagnostic.correctionDeltaBeats <= 0.0050001);
    double positiveCorrection = 0.0;
    for (int i = 0; i < 60; ++i) {
        rig.tick();
        const auto& d = rig.lastDiagnostic;
        positiveCorrection += d.correctionDeltaBeats;
        CHECK(std::abs(d.correctionDeltaBeats) <= 0.0050001);
        CHECK(std::abs(d.actualDeltaBeats - d.baseDeltaBeats - d.correctionDeltaBeats) < 1e-10);
        const double effectiveBpm = 60.0 * d.actualDeltaBeats / (480.0 / 48000.0);
        CHECK_NEAR(effectiveBpm, 60.0 * (d.baseDeltaBeats + d.correctionDeltaBeats) / 0.01, 1e-8);
    }
    CHECK(positiveCorrection > 0.0);
    CHECK_NEAR(rig.transport.snapshot().transportBpm, 123.0, 1e-9);
    CHECK(60.0 * positiveCorrection / 0.6 > 0.0); // effective cursor departed from base due measured phase slew.

    frame = rig.sourceFrame;
    rig.submit(frame, -0.25, 2);
    rig.tick();
    double negativeCorrection = 0.0;
    for (int i = 0; i < 60; ++i) {
        rig.tick();
        const auto& d = rig.lastDiagnostic;
        negativeCorrection += d.correctionDeltaBeats;
        CHECK(std::abs(d.correctionDeltaBeats) <= 0.0050001);
        CHECK(std::abs(d.actualDeltaBeats - d.baseDeltaBeats - d.correctionDeltaBeats) < 1e-10);
    }
    CHECK(negativeCorrection < 0.0);
    CHECK_NEAR(rig.transport.snapshot().transportBpm, 123.0, 1e-9);
}

void test_transport_continuity_epoch_invalidates_confidence_without_reposition() {
    TransportRig rig;
    for (int i = 0; i < 10; ++i) rig.tick();
    const double beforeGap = rig.transport.snapshot().quarterBeatPosition;
    rig.tick(480, 2); // ring reports next contiguous data after dropped PCM.
    const auto after = rig.transport.snapshot();
    CHECK(after.positionStateCode == static_cast<int>(PositionTrackingState::Reacquiring));
    CHECK(after.positionConfidence == 0.0);
    CHECK(after.quarterBeatPosition > beforeGap);
    CHECK(after.quarterBeatPosition < beforeGap + 0.1);
}

void test_transport_position_reset_rejects_queued_pre_reset_match() {
    TransportRig rig;
    for (int i = 0; i < 10; ++i) rig.tick();
    const uint64_t oldGeneration = rig.generation;
    const uint64_t frame = rig.sourceFrame;
    rig.submit(frame, 10.0, 1);
    rig.transport.resetPosition(50.0);
    rig.tick();
    CHECK(rig.lastDiagnostic.rejectionCode == 1);
    CHECK(rig.generation != oldGeneration);
    CHECK(rig.transport.snapshot().quarterBeatPosition > 50.0);
    CHECK(rig.transport.snapshot().quarterBeatPosition < 50.1);
}

void test_transport_history_interpolates_integrated_base_and_corrections() {
    TransportHistory history;
    history.append(TransportHistorySegment{
        5, 2, 9, 1000, 100, 7.2, 30.0, 0.5, 0.1, 0.2,
    });
    TransportHistoryPoint point;
    CHECK(history.lookup(AudioSourceSpan{5, 2, 9, 1050, 0}, point));
    CHECK_NEAR(point.baseBeats, 30.25, 1e-12);
    CHECK_NEAR(point.position, 7.50, 1e-12);
    CHECK(history.lookup(AudioSourceSpan{5, 2, 9, 1100, 0}, point));
    CHECK_NEAR(point.baseBeats, 30.5, 1e-12);
    CHECK_NEAR(point.position, 7.8, 1e-12); // endpoint is sourceStart plus integrated segment deltas.
    CHECK(!history.lookup(AudioSourceSpan{5, 3, 9, 1050, 0}, point));
}

void test_history_spsc_overflow_fails_closed_without_overwriting_old_slots() {
    TransportHistoryChannel channel;
    TransportHistorySegment segment;
    segment.streamEpoch = 1;
    segment.frameCount = 1;
    for (uint32_t i = 0; i < TransportHistoryChannel::Capacity; ++i) {
        segment.sourceStartFrame = i;
        CHECK(channel.push(segment));
    }
    segment.sourceStartFrame = TransportHistoryChannel::Capacity;
    CHECK(!channel.push(segment));
    CHECK(channel.overflowCount() == 1);
    TransportHistorySegment read;
    CHECK(channel.pop(read));
    CHECK(read.sourceStartFrame == 0);
    CHECK(channel.push(segment)); // room returned only after consumer release.
    for (uint32_t i = 1; i < TransportHistoryChannel::Capacity; ++i) {
        CHECK(channel.pop(read));
        CHECK(read.sourceStartFrame == i);
    }
    CHECK(channel.pop(read));
    CHECK(read.sourceStartFrame == TransportHistoryChannel::Capacity);
    CHECK(!channel.pop(read));
}

void test_transport_missing_historical_coverage_rejects_even_young_observation() {
    LiveTransport transport;
    transport.configure(123.0, 0.0);
    transport.beginStreamEpoch(91);
    for (uint64_t frame = 0; frame <= TransportHistory::Capacity; ++frame)
        transport.processSourceFrames(1, 48000, 91, 1, frame);
    transport.enableDiagnostics(true);
    PositionObservation observation;
    observation.quarterBeatPosition = 0.0;
    observation.confidence = 0.95f;
    observation.ambiguityMargin = 0.3f;
    observation.valid = true;
    observation.resetGeneration = transport.positionGeneration();
    observation.streamEpoch = 91;
    observation.continuityEpoch = 1;
    observation.observationFrame = 0;
    observation.sequence = 1;
    transport.submitPositionObservation(observation, 0.0);
    transport.processSourceFrames(1, 48000, 91, 1, TransportHistory::Capacity + 1);
    TransportDiagnosticRecord record;
    while (transport.popDiagnostic(record)) {}
    CHECK(record.observationAgeFrames == TransportHistory::Capacity + 1);
    CHECK(record.rejectionCode == 4); // history no longer covers a <1 s old source frame.
    CHECK(transport.historyOverflowCount() > 0); // the worker-transfer queue also failed closed.
}

void test_capture_watermark_advances_without_worker_history_drain_and_across_epochs() {
    SpscAudioRing ring(8);
    LiveTransport transport;
    transport.configure(123.0, 0.0);
    transport.beginStreamEpoch(101);
    AudioAnalyzer analyzer(ring);
    analyzer.configureMatcher(nullptr, &transport); // worker deliberately not started/draining.

    constexpr uint64_t frames = TransportHistory::Capacity + 5;
    for (uint64_t frame = 0; frame < frames; ++frame)
        transport.processSourceFrames(1, 48000, 101, 1, frame);
    auto diagnostics = analyzer.diagnostics();
    CHECK(diagnostics.streamEpoch == 101);
    CHECK(diagnostics.capturedFrames == frames);
    CHECK(diagnostics.latestCapturedFrame == frames);
    CHECK(diagnostics.latestHistoryFrame == 0);
    CHECK(transport.historyOverflowCount() == 5);

    // Per-stream source frame resets to zero, while monotonic captured
    // watermark continues across restart and cannot masquerade as backlog.
    transport.beginStreamEpoch(102);
    transport.processSourceFrames(7, 48000, 102, 1, 0);
    diagnostics = analyzer.diagnostics();
    CHECK(diagnostics.streamEpoch == 102);
    CHECK(diagnostics.capturedFrames == frames + 7);
    CHECK(diagnostics.latestCapturedFrame == frames + 7);
    CHECK(diagnostics.latestHistoryFrame == 0);
}

REGISTER_TEST(test_transport_observation_age_uses_historical_integrated_base);
REGISTER_TEST(test_transport_rejects_stale_wrong_epoch_and_duplicate_observations);
REGISTER_TEST(test_delayed_match_projection_subtracts_correction_applied_after_match_frame);
REGISTER_TEST(test_transport_effective_rate_tracks_bounded_positive_and_negative_corrections);
REGISTER_TEST(test_transport_continuity_epoch_invalidates_confidence_without_reposition);
REGISTER_TEST(test_transport_position_reset_rejects_queued_pre_reset_match);
REGISTER_TEST(test_transport_history_interpolates_integrated_base_and_corrections);
REGISTER_TEST(test_history_spsc_overflow_fails_closed_without_overwriting_old_slots);
REGISTER_TEST(test_transport_missing_historical_coverage_rejects_even_young_observation);
REGISTER_TEST(test_capture_watermark_advances_without_worker_history_drain_and_across_epochs);
