#include "transport/LiveTransport.h"

#include <algorithm>
#include <cmath>

namespace temposcore {

void LiveTransport::configure(double expectedBpm, double startQuarterBeat) noexcept {
    if (!std::isfinite(expectedBpm)) expectedBpm = 120.0;
    expectedBpm = std::clamp(expectedBpm, 30.0, 300.0);
    requestedExpectedBpm_.store(expectedBpm, std::memory_order_relaxed);
    requestedPosition_.store(std::max(0.0, startQuarterBeat), std::memory_order_relaxed);
    positionGeneration_.fetch_add(1, std::memory_order_release);
    expectedBpmRt_ = expectedBpm;
    currentBpmRt_ = expectedBpm;
    positionRt_ = std::max(0.0, startQuarterBeat);
    publishedBpm_.store(currentBpmRt_, std::memory_order_relaxed);
    publishedPosition_.store(positionRt_, std::memory_order_relaxed);
    remainingPositionErrorRt_ = 0.0;
    correctionValid_.store(false, std::memory_order_relaxed);
    correctionGeneration_.fetch_add(1, std::memory_order_release);
    tempoTargetBpm_.store(0.0); tempoTargetConfidence_.store(0.0f);
    tempoTargetValid_.store(false); tempoDetectedBpm_.store(0.0); tempoRms_.store(0.0);
    tempoVersion_.fetch_add(2, std::memory_order_release);
}

void LiveTransport::resetPosition(double startQuarterBeat) noexcept {
    const double clamped = std::max(0.0, startQuarterBeat);
    requestedPosition_.store(clamped, std::memory_order_relaxed);
    publishedPosition_.store(clamped, std::memory_order_relaxed);
    positionGeneration_.fetch_add(1, std::memory_order_release);
}

void LiveTransport::setExpectedBpm(double expectedBpm) noexcept {
    if (!std::isfinite(expectedBpm)) return;
    const double clamped = std::clamp(expectedBpm, 30.0, 300.0);
    requestedExpectedBpm_.store(clamped, std::memory_order_relaxed);
    if (!running_.load(std::memory_order_relaxed)) {
        publishedBpm_.store(clamped, std::memory_order_relaxed);
    }
}

void LiveTransport::setRunning(bool running) noexcept {
    running_.store(running, std::memory_order_release);
}
void LiveTransport::publishIdle() noexcept {
    // Stop (spec §30): the score follower is no longer active. Publish Idle and
    // clear the position diagnostics so the UI does not show a stale state.
    publishedPositionState_.store(0,std::memory_order_relaxed);
    publishedPositionConfidence_.store(0.0,std::memory_order_relaxed);
    publishedPositionError_.store(0.0,std::memory_order_relaxed);
    publishedAmbiguity_.store(0.0,std::memory_order_relaxed);
    correctionValid_.store(false,std::memory_order_relaxed);
    correctionErrorBeats_.store(0.0,std::memory_order_relaxed);
    correctionGeneration_.fetch_add(1,std::memory_order_release);
    tempoVersion_.fetch_add(1, std::memory_order_relaxed);
    tempoTargetValid_.store(false, std::memory_order_release);
    tempoTargetBpm_.store(0.0, std::memory_order_relaxed);
    tempoTargetConfidence_.store(0.0f, std::memory_order_relaxed);
    tempoDetectedBpm_.store(0.0, std::memory_order_relaxed);
    tempoRms_.store(0.0, std::memory_order_relaxed);
    publishedDetectedBpm_.store(0.0, std::memory_order_relaxed);
    tempoVersion_.fetch_add(1, std::memory_order_release);
}

void LiveTransport::submitPositionObservation(const PositionObservation&o, double validContextSeconds) noexcept {
    // Always publish the latest score-following state (even when the match is
    // not confident enough to set a correction target), so the UI can show the
    // current state / "locating" progress.
    publishedPositionConfidence_.store(o.confidence,std::memory_order_relaxed);
    publishedMatchedPosition_.store(o.quarterBeatPosition,std::memory_order_relaxed);
    // Read the published position (atomic) — not the RT positionRt_ (plain,
    // written by the Oboe callback) — to avoid a cross-thread data race.
    const double predicted = publishedPosition_.load(std::memory_order_relaxed);
    const double errorBeats = o.quarterBeatPosition - predicted;
    publishedPositionError_.store(errorBeats,std::memory_order_relaxed);
    publishedPositionState_.store(static_cast<int>(o.state),std::memory_order_relaxed);
    publishedAmbiguity_.store(o.ambiguityMargin,std::memory_order_relaxed);
    publishedValidContextSeconds_.store(validContextSeconds,std::memory_order_relaxed);

    // Publish each observation as an ERROR OFFSET.  A frozen target position is
    // wrong here: while a 2-4 beat correction is being slewed, both the player
    // and transport continue advancing.  Chasing the old absolute target causes
    // the cursor to remain late at section boundaries.
    correctionErrorBeats_.store(errorBeats,std::memory_order_relaxed);
    correctionConfidence_.store(o.confidence,std::memory_order_relaxed);
    correctionAmbiguity_.store(o.ambiguityMargin,std::memory_order_relaxed);
    correctionGlobal_.store(o.globalMatch,std::memory_order_relaxed);
    correctionValid_.store(o.valid,std::memory_order_relaxed);
    correctionGeneration_.fetch_add(1,std::memory_order_release);
}

void LiveTransport::submitTempoObservation(const BeatObservation& observation) noexcept {
    const bool finiteBpm = std::isfinite(observation.detectedBpm);
    const bool valid = observation.tempoValid && finiteBpm && observation.detectedBpm > 0.0;
    const double bpm = valid ? std::clamp(observation.detectedBpm, 30.0, 300.0) : 0.0;
    const float confidence = std::clamp(std::isfinite(observation.confidence) ? observation.confidence : 0.0f, 0.0f, 1.0f);
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
    if (numFrames <= 0 || sampleRate <= 0) return;

    expectedBpmRt_ = requestedExpectedBpm_.load(std::memory_order_relaxed);
    const uint64_t generation = positionGeneration_.load(std::memory_order_acquire);
    if (generation != appliedPositionGeneration_) {
        positionRt_ = requestedPosition_.load(std::memory_order_relaxed);
        remainingPositionErrorRt_ = 0.0;
        appliedPositionGeneration_ = generation;
    }

    bool valid = false; double target = 0.0; double confidence = 0.0;
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
    const double tau = valid ? (confidence >= 0.75 ? 0.7 : 1.2) : 4.0;
    const double alpha = 1.0 - std::exp(-static_cast<double>(numFrames) / sampleRate / tau);
    const double boundedTarget = valid
        ? std::clamp(target, expectedBpmRt_ * 0.55, expectedBpmRt_ * 1.80)
        : expectedBpmRt_;
    currentBpmRt_ += alpha * (boundedTarget - currentBpmRt_);

    // Never let a noisy tracker instantly run away from the MIDI prior.
    currentBpmRt_ = std::clamp(currentBpmRt_,
                               expectedBpmRt_ * 0.55,
                               expectedBpmRt_ * 1.80);

    positionRt_ += (currentBpmRt_ / 60.0) * (static_cast<double>(numFrames) / sampleRate);

    // Latch a new slow-loop observation once.  The RT loop then owns the
    // remaining offset; it must NOT repeatedly chase a frozen absolute target.
    const uint64_t correctionGeneration = correctionGeneration_.load(std::memory_order_acquire);
    if (correctionGeneration != appliedCorrectionGeneration_) {
        appliedCorrectionGeneration_ = correctionGeneration;
        const bool corrValid = correctionValid_.load(std::memory_order_relaxed);
        if (!corrValid) {
            remainingPositionErrorRt_ = 0.0;
        } else {
            const double error = correctionErrorBeats_.load(std::memory_order_relaxed);
            const double conf = correctionConfidence_.load(std::memory_order_relaxed);
            const double ambig = correctionAmbiguity_.load(std::memory_order_relaxed);
            const bool isGlobal = correctionGlobal_.load(std::memory_order_relaxed);
            const double a = std::abs(error);
            // ambiguityMargin is second-best minus best: LARGER means more unique.
            const bool unique = ambig >= .05;
            const bool strong = conf >= .88 && unique;
            if (a > 4.0) {
                // Never relocate to a repeated/look-alike section merely because
                // it is the current top-1.  Initial/global relocation also needs
                // a reasonably unique, high-confidence observation.
                if (strong || (isGlobal && conf >= .80 && unique)) positionRt_ += error;
                remainingPositionErrorRt_ = 0.0;
            } else {
                remainingPositionErrorRt_ = error;
            }
        }
    }

    if (std::abs(remainingPositionErrorRt_) > 1e-6) {
        const double a = std::abs(remainingPositionErrorRt_);
        if (a < .50) {
            // Tiny phase error: remove it immediately; this is below a visible
            // fraction of a beat and avoids a permanently biased cursor.
            positionRt_ += remainingPositionErrorRt_;
            remainingPositionErrorRt_ = 0.0;
        } else {
            const double dt = static_cast<double>(numFrames) / sampleRate;
            // Correct the OFFSET while the nominal song position keeps moving.
            // Faster than the old 0.1*error P-loop, but still bounded to avoid a
            // visible jump when the matcher is a beat or two off.
            const double rate = std::min(0.50, std::max(0.15, 0.20 * a));
            const double step = std::clamp(remainingPositionErrorRt_, -rate * dt, rate * dt);
            positionRt_ += step;
            remainingPositionErrorRt_ -= step;
        }
    }
    positionRt_ = std::max(0.0, positionRt_);

    publishedBpm_.store(currentBpmRt_, std::memory_order_relaxed);
    publishedDetectedBpm_.store(valid ? tempoDetectedBpm_.load(std::memory_order_relaxed) : 0.0, std::memory_order_relaxed);
    publishedPosition_.store(positionRt_, std::memory_order_relaxed);
    publishedConfidence_.store(valid ? confidence : 0.0, std::memory_order_relaxed);
    publishedRms_.store(tempoRms_.load(std::memory_order_relaxed), std::memory_order_relaxed);
}

TransportState LiveTransport::snapshot() const noexcept {
    TransportState s;
    s.transportBpm = publishedBpm_.load(std::memory_order_relaxed);
    s.detectedBpm = publishedDetectedBpm_.load(std::memory_order_relaxed);
    s.quarterBeatPosition = publishedPosition_.load(std::memory_order_relaxed);
    s.confidence = publishedConfidence_.load(std::memory_order_relaxed);
    s.rms = publishedRms_.load(std::memory_order_relaxed);
    s.running = running_.load(std::memory_order_acquire);
    s.positionConfidence = publishedPositionConfidence_.load(std::memory_order_relaxed);
    s.matchedQuarterBeatPosition = publishedMatchedPosition_.load(std::memory_order_relaxed);
    s.positionErrorBeats = publishedPositionError_.load(std::memory_order_relaxed);
    s.positionStateCode = publishedPositionState_.load(std::memory_order_relaxed);
    s.ambiguityMargin = publishedAmbiguity_.load(std::memory_order_relaxed);
    s.validContextSeconds = publishedValidContextSeconds_.load(std::memory_order_relaxed);
    return s;
}

} // namespace temposcore
