#include "transport/LiveTransport.h"

#include <algorithm>
#include <cmath>

namespace temposcore {
namespace {
constexpr uint32_t RejectNone = 0;
constexpr uint32_t RejectWrongEpoch = 1;
constexpr uint32_t RejectFutureFrame = 2;
constexpr uint32_t RejectStaleFrame = 3;
constexpr uint32_t RejectHistoryMiss = 4;
constexpr uint32_t RejectDuplicate = 5;
}

LiveTransport::LiveTransport()
    : callbackHistory_(new TransportHistory),
      historyChannel_(new TransportHistoryChannel),
      diagnosticChannel_(new TransportDiagnosticsChannel) {}

void LiveTransport::beginStreamEpoch(uint64_t streamEpoch) noexcept {
    streamEpochRt_ = streamEpoch ? streamEpoch : 1;
    publishedStreamEpoch_.store(streamEpochRt_, std::memory_order_release);
    legacySourceFrame_ = 0;
    cumulativeBaseBeatsRt_ = 0.0;
    lastAppliedObservationSequence_ = 0;
    lastContinuityEpochRt_ = 0;
    remainingPositionErrorRt_ = 0.0;
    invalidatePositionState();
    callbackHistory_->clear();
    historyChannel_->clearStopped();
    diagnosticChannel_->clearStopped();
    observationHead_.store(0, std::memory_order_relaxed);
    observationTail_.store(0, std::memory_order_relaxed);
    droppedObservations_.store(0, std::memory_order_relaxed);
}

void LiveTransport::configure(double expectedBpm, double startQuarterBeat) noexcept {
    if (!std::isfinite(expectedBpm)) expectedBpm = 120.0;
    expectedBpm = std::clamp(expectedBpm, 30.0, 300.0);
    requestedExpectedBpm_.store(expectedBpm, std::memory_order_relaxed);
    requestedPosition_.store(std::max(0.0, startQuarterBeat), std::memory_order_relaxed);
    positionGeneration_.fetch_add(1, std::memory_order_release);
    expectedBpmRt_ = expectedBpm;
    currentBpmRt_ = expectedBpm;
    positionRt_ = std::max(0.0, startQuarterBeat);
    cumulativeBaseBeatsRt_ = 0.0;
    publishedBpm_.store(currentBpmRt_, std::memory_order_relaxed);
    publishedPosition_.store(positionRt_, std::memory_order_relaxed);
    remainingPositionErrorRt_ = 0.0;
    lastAppliedObservationSequence_ = 0;
    tempoTargetBpm_.store(0.0);
    tempoTargetConfidence_.store(0.0f);
    tempoTargetValid_.store(false);
    tempoDetectedBpm_.store(0.0);
    tempoRms_.store(0.0);
    tempoVersion_.fetch_add(2, std::memory_order_release);
}

void LiveTransport::resetPosition(double startQuarterBeat) noexcept {
    const double clamped = std::max(0.0, startQuarterBeat);
    requestedPosition_.store(clamped, std::memory_order_relaxed);
    publishedPosition_.store(clamped, std::memory_order_relaxed);
    positionGeneration_.fetch_add(1, std::memory_order_release);
    publishedPositionConfidence_.store(0.0, std::memory_order_relaxed);
    publishedPositionError_.store(0.0, std::memory_order_relaxed);
    publishedPositionState_.store(0, std::memory_order_relaxed);
}

void LiveTransport::setExpectedBpm(double expectedBpm) noexcept {
    if (!std::isfinite(expectedBpm)) return;
    const double clamped = std::clamp(expectedBpm, 30.0, 300.0);
    requestedExpectedBpm_.store(clamped, std::memory_order_relaxed);
    if (!running_.load(std::memory_order_relaxed)) publishedBpm_.store(clamped, std::memory_order_relaxed);
}

void LiveTransport::setRunning(bool running) noexcept {
    running_.store(running, std::memory_order_release);
}

void LiveTransport::publishIdle() noexcept {
    publishedPositionState_.store(0, std::memory_order_relaxed);
    publishedPositionConfidence_.store(0.0, std::memory_order_relaxed);
    publishedPositionError_.store(0.0, std::memory_order_relaxed);
    publishedAmbiguity_.store(0.0, std::memory_order_relaxed);
    tempoVersion_.fetch_add(1, std::memory_order_relaxed);
    tempoTargetValid_.store(false, std::memory_order_release);
    tempoTargetBpm_.store(0.0, std::memory_order_relaxed);
    tempoTargetConfidence_.store(0.0f, std::memory_order_relaxed);
    tempoDetectedBpm_.store(0.0, std::memory_order_relaxed);
    tempoRms_.store(0.0, std::memory_order_relaxed);
    publishedDetectedBpm_.store(0.0, std::memory_order_relaxed);
    tempoVersion_.fetch_add(1, std::memory_order_release);
}

void LiveTransport::invalidatePositionState() noexcept {
    publishedPositionConfidence_.store(0.0, std::memory_order_relaxed);
    publishedPositionState_.store(static_cast<int>(PositionTrackingState::Reacquiring),
                                  std::memory_order_relaxed);
    publishedValidContextSeconds_.store(0.0, std::memory_order_relaxed);
}

bool LiveTransport::enqueueObservation(const PendingObservation& observation) noexcept {
    const uint32_t head = observationHead_.load(std::memory_order_relaxed);
    const uint32_t tail = observationTail_.load(std::memory_order_acquire);
    if (head - tail >= ObservationCapacity) return false;
    observations_[head % ObservationCapacity] = observation;
    observationHead_.store(head + 1, std::memory_order_release);
    return true;
}

bool LiveTransport::dequeueObservation(PendingObservation& observation) noexcept {
    const uint32_t tail = observationTail_.load(std::memory_order_relaxed);
    if (tail == observationHead_.load(std::memory_order_acquire)) return false;
    observation = observations_[tail % ObservationCapacity];
    observationTail_.store(tail + 1, std::memory_order_release);
    return true;
}

void LiveTransport::submitPositionObservation(const PositionObservation& observation,
                                             double validContextSeconds) noexcept {
    PendingObservation pending;
    pending.observation = observation;
    pending.validContextSeconds = validContextSeconds;
    if (!enqueueObservation(pending)) droppedObservations_.fetch_add(1, std::memory_order_relaxed);
}

void LiveTransport::submitTempoObservation(const BeatObservation& observation) noexcept {
    const bool finiteBpm = std::isfinite(observation.detectedBpm);
    const bool valid = observation.tempoValid && finiteBpm && observation.detectedBpm > 0.0;
    const double bpm = valid ? std::clamp(observation.detectedBpm, 30.0, 300.0) : 0.0;
    const float confidence = std::clamp(std::isfinite(observation.confidence) ? observation.confidence : 0.0f,
                                        0.0f, 1.0f);
    const double rms = std::max(0.0, std::isfinite(observation.rms) ? static_cast<double>(observation.rms) : 0.0);
    tempoVersion_.fetch_add(1, std::memory_order_relaxed);
    tempoTargetBpm_.store(bpm, std::memory_order_relaxed);
    tempoTargetConfidence_.store(confidence, std::memory_order_relaxed);
    tempoDetectedBpm_.store(bpm, std::memory_order_relaxed);
    tempoRms_.store(rms, std::memory_order_relaxed);
    tempoTargetValid_.store(valid, std::memory_order_release);
    tempoVersion_.fetch_add(1, std::memory_order_release);
}

void LiveTransport::processFrames(int32_t numFrames, int32_t sampleRate) noexcept {
    if (numFrames <= 0) return;
    const uint64_t firstFrame = legacySourceFrame_;
    legacySourceFrame_ += static_cast<uint64_t>(numFrames);
    processFramesImpl(numFrames, sampleRate, streamEpochRt_, 1, firstFrame);
}

uint64_t LiveTransport::processSourceFrames(int32_t numFrames, int32_t sampleRate,
                                            uint64_t streamEpoch, uint64_t continuityEpoch,
                                            uint64_t firstSourceFrame) noexcept {
    if (numFrames > 0)
        processFramesImpl(numFrames, sampleRate, streamEpoch, continuityEpoch, firstSourceFrame);
    return appliedPositionGeneration_;
}

void LiveTransport::processFramesImpl(int32_t numFrames, int32_t sampleRate,
                                      uint64_t streamEpoch, uint64_t continuityEpoch,
                                      uint64_t firstSourceFrame) noexcept {
    if (numFrames <= 0 || sampleRate <= 0 || streamEpoch != streamEpochRt_) return;
    capturedFrameWatermark_.fetch_add(static_cast<uint64_t>(numFrames), std::memory_order_release);
    expectedBpmRt_ = requestedExpectedBpm_.load(std::memory_order_relaxed);
    const uint64_t generation = positionGeneration_.load(std::memory_order_acquire);
    if (generation != appliedPositionGeneration_) {
        positionRt_ = requestedPosition_.load(std::memory_order_relaxed);
        remainingPositionErrorRt_ = 0.0;
        appliedPositionGeneration_ = generation;
        invalidatePositionState();
    }
    if (lastContinuityEpochRt_ && continuityEpoch != lastContinuityEpochRt_) {
        remainingPositionErrorRt_ = 0.0;
        invalidatePositionState();
    }
    lastContinuityEpochRt_ = continuityEpoch;

    bool valid = false;
    double target = 0.0;
    double confidence = 0.0;
    for (int attempt = 0; attempt < 3; ++attempt) {
        const uint64_t before = tempoVersion_.load(std::memory_order_acquire);
        if (before & 1U) continue;
        target = tempoTargetBpm_.load(std::memory_order_relaxed);
        confidence = tempoTargetConfidence_.load(std::memory_order_relaxed);
        valid = tempoTargetValid_.load(std::memory_order_relaxed);
        if (before == tempoVersion_.load(std::memory_order_acquire)) break;
        valid = false;
    }
    if (!std::isfinite(target) || !std::isfinite(confidence)) { valid = false; target = 0.0; confidence = 0.0; }

    // Consume observations at this callback's source-start boundary, before
    // integrating this interval. This timestamps age and projected error at
    // actual arrival boundary rather than charging current callback duration.
    double rawObservationError = 0.0;
    double projectedError = 0.0;
    double startRelocation = 0.0;
    uint64_t observationSequence = 0;
    uint64_t observationAge = 0;
    uint32_t rejectionCode = RejectNone;
    PendingObservation pending;
    for (uint32_t processed = 0; processed < ObservationCapacity && dequeueObservation(pending); ++processed) {
        const PositionObservation& observation = pending.observation;
        if (observation.sequence == 0 || observation.sequence <= lastAppliedObservationSequence_) {
            rejectionCode = RejectDuplicate;
            continue;
        }
        lastAppliedObservationSequence_ = observation.sequence;
        observationSequence = observation.sequence;
        if (observation.streamEpoch != streamEpoch || observation.continuityEpoch != continuityEpoch ||
            observation.resetGeneration != generation) {
            rejectionCode = RejectWrongEpoch;
        } else if (observation.observationFrame > firstSourceFrame) {
            rejectionCode = RejectFutureFrame;
        } else if (firstSourceFrame - observation.observationFrame > static_cast<uint64_t>(sampleRate) * 2) {
            rejectionCode = RejectStaleFrame;
            observationAge = firstSourceFrame - observation.observationFrame;
        } else {
            observationAge = firstSourceFrame - observation.observationFrame;
            AudioSourceSpan stamp{streamEpoch, continuityEpoch, generation, observation.observationFrame, 0};
            TransportHistoryPoint point;
            if (!callbackHistory_->lookup(stamp, point)) {
                rejectionCode = RejectHistoryMiss;
            } else {
                rejectionCode = RejectNone;
                rawObservationError = observation.quarterBeatPosition - point.position;
                projectedError = observation.quarterBeatPosition +
                    (cumulativeBaseBeatsRt_ - point.baseBeats) - positionRt_;
                publishedPositionConfidence_.store(observation.confidence, std::memory_order_relaxed);
                publishedMatchedPosition_.store(observation.quarterBeatPosition, std::memory_order_relaxed);
                publishedPositionError_.store(rawObservationError, std::memory_order_relaxed);
                publishedPositionState_.store(static_cast<int>(observation.state), std::memory_order_relaxed);
                publishedAmbiguity_.store(observation.ambiguityMargin, std::memory_order_relaxed);
                publishedValidContextSeconds_.store(pending.validContextSeconds, std::memory_order_relaxed);

                if (!observation.valid) {
                    remainingPositionErrorRt_ = 0.0;
                } else {
                    const double magnitude = std::abs(projectedError);
                    const bool unique = observation.ambiguityMargin >= .05;
                    const bool strong = observation.confidence >= .88 && unique;
                    if (magnitude > 4.0) {
                        if (strong || (observation.globalMatch && observation.confidence >= .80 && unique)) {
                            positionRt_ += projectedError;
                            startRelocation += projectedError;
                        }
                        remainingPositionErrorRt_ = 0.0;
                    } else {
                        remainingPositionErrorRt_ = projectedError;
                    }
                }
            }
        }
        if (rejectionCode != RejectNone) {
            publishedPositionConfidence_.store(0.0, std::memory_order_relaxed);
            publishedPositionState_.store(static_cast<int>(PositionTrackingState::Reacquiring),
                                          std::memory_order_relaxed);
            remainingPositionErrorRt_ = 0.0;
        }
    }

    const double positionStart = std::max(0.0, positionRt_);
    const double baseBeatsStart = cumulativeBaseBeatsRt_;
    const double dt = static_cast<double>(numFrames) / sampleRate;
    const double tau = valid ? (confidence >= 0.75 ? 0.7 : 1.2) : 4.0;
    const double alpha = 1.0 - std::exp(-dt / tau);
    const double boundedTarget = valid
        ? std::clamp(target, expectedBpmRt_ * 0.55, expectedBpmRt_ * 1.80)
        : expectedBpmRt_;
    currentBpmRt_ += alpha * (boundedTarget - currentBpmRt_);
    currentBpmRt_ = std::clamp(currentBpmRt_, expectedBpmRt_ * 0.55, expectedBpmRt_ * 1.80);

    const double baseDelta = (currentBpmRt_ / 60.0) * dt;
    positionRt_ += baseDelta;
    cumulativeBaseBeatsRt_ += baseDelta;
    double correctionDelta = 0.0;
    if (std::abs(remainingPositionErrorRt_) > 1e-6) {
        const double magnitude = std::abs(remainingPositionErrorRt_);
        // Preserve existing bounded slew limits. Small offsets now use this same
        // slew rather than an instantaneous sub-half-beat jump.
        const double rate = std::min(0.50, std::max(0.15, 0.20 * magnitude));
        correctionDelta = std::clamp(remainingPositionErrorRt_, -rate * dt, rate * dt);
        positionRt_ += correctionDelta;
        remainingPositionErrorRt_ -= correctionDelta;
    }

    const uint64_t sourceEndFrame = firstSourceFrame + static_cast<uint64_t>(numFrames);
    positionRt_ = std::max(0.0, positionRt_);
    TransportHistorySegment segment;
    segment.streamEpoch = streamEpoch;
    segment.continuityEpoch = continuityEpoch;
    segment.positionGeneration = generation;
    segment.sourceStartFrame = firstSourceFrame;
    segment.frameCount = static_cast<uint32_t>(numFrames);
    segment.positionStart = positionStart;
    segment.baseBeatsStart = baseBeatsStart;
    segment.baseDeltaBeats = baseDelta;
    segment.correctionDeltaBeats = correctionDelta;
    segment.startRelocationBeats = startRelocation;
    callbackHistory_->append(segment);
    historyChannel_->push(segment); // bounded SPSC; failure is observable and never blocks RT.

    if (diagnosticsEnabled_.load(std::memory_order_relaxed)) {
        TransportDiagnosticRecord diagnostic;
        diagnostic.streamEpoch = streamEpoch;
        diagnostic.continuityEpoch = continuityEpoch;
        diagnostic.positionGeneration = generation;
        diagnostic.sourceEndFrame = sourceEndFrame;
        diagnostic.observationSequence = observationSequence;
        diagnostic.observationAgeFrames = observationAge;
        diagnostic.baseDeltaBeats = baseDelta;
        diagnostic.correctionDeltaBeats = correctionDelta;
        diagnostic.actualDeltaBeats = positionRt_ - positionStart;
        diagnostic.relocationDeltaBeats = startRelocation;
        diagnostic.rawObservationError = rawObservationError;
        diagnostic.projectedCorrectionError = projectedError;
        diagnostic.rejectionCode = rejectionCode;
        diagnosticChannel_->push(diagnostic);
    }

    publishedBpm_.store(currentBpmRt_, std::memory_order_relaxed);
    publishedDetectedBpm_.store(valid ? tempoDetectedBpm_.load(std::memory_order_relaxed) : 0.0,
                                std::memory_order_relaxed);
    publishedPosition_.store(positionRt_, std::memory_order_relaxed);
    publishedConfidence_.store(valid ? confidence : 0.0, std::memory_order_relaxed);
    publishedRms_.store(tempoRms_.load(std::memory_order_relaxed), std::memory_order_relaxed);
}

TransportState LiveTransport::snapshot() const noexcept {
    TransportState state;
    state.transportBpm = publishedBpm_.load(std::memory_order_relaxed);
    state.detectedBpm = publishedDetectedBpm_.load(std::memory_order_relaxed);
    state.quarterBeatPosition = publishedPosition_.load(std::memory_order_relaxed);
    state.confidence = publishedConfidence_.load(std::memory_order_relaxed);
    state.rms = publishedRms_.load(std::memory_order_relaxed);
    state.running = running_.load(std::memory_order_acquire);
    state.positionConfidence = publishedPositionConfidence_.load(std::memory_order_relaxed);
    state.matchedQuarterBeatPosition = publishedMatchedPosition_.load(std::memory_order_relaxed);
    state.positionErrorBeats = publishedPositionError_.load(std::memory_order_relaxed);
    state.positionStateCode = publishedPositionState_.load(std::memory_order_relaxed);
    state.ambiguityMargin = publishedAmbiguity_.load(std::memory_order_relaxed);
    state.validContextSeconds = publishedValidContextSeconds_.load(std::memory_order_relaxed);
    return state;
}

} // namespace temposcore
