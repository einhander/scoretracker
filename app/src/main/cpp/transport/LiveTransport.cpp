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
    targetActive_.store(false,std::memory_order_relaxed);
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
    publishedPositionError_.store(o.quarterBeatPosition - publishedPosition_.load(std::memory_order_relaxed),std::memory_order_relaxed);
    publishedPositionState_.store(static_cast<int>(o.state),std::memory_order_relaxed);
    publishedAmbiguity_.store(o.ambiguityMargin,std::memory_order_relaxed);
    publishedValidContextSeconds_.store(validContextSeconds,std::memory_order_relaxed);
    if (!o.valid) return; // only a confident match sets a correction target
    targetPosition_.store(o.quarterBeatPosition,std::memory_order_relaxed);
    targetConfidence_.store(o.confidence,std::memory_order_relaxed);
    targetAmbiguity_.store(o.ambiguityMargin,std::memory_order_relaxed);
    targetGlobal_.store(o.globalMatch,std::memory_order_relaxed);
    targetActive_.store(true,std::memory_order_release);
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
    if (targetActive_.load(std::memory_order_acquire)) {
        const double target = targetPosition_.load(std::memory_order_relaxed);
        const double conf = targetConfidence_.load(std::memory_order_relaxed);
        const double ambig = targetAmbiguity_.load(std::memory_order_relaxed);
        const bool isGlobal = targetGlobal_.load(std::memory_order_relaxed);
        const double error = target - positionRt_;
        const double a = std::abs(error);
        const bool strong = conf >= .88 && ambig < .05;
        if (a < .5) {
            // Converged: snap the remainder and retire the target.
            positionRt_ += error;
            targetActive_.store(false, std::memory_order_relaxed);
        } else if (a <= 4) {
            // Rate-limited slew toward the target on EVERY callback: the slew
            // is capped at 0.4 beat/s and decays with the remaining error
            // (P-control: 10% of the error per second, capped at 0.4 beat/s).
            // The target persists until the cursor reaches it, so the slew is
            // applied every callback (not once per ~2 s observation, which would
            // be ~200x too slow). The total approach is faster at normal BPM
            // because the frame-driven advance also moves the cursor.
            const double dt = static_cast<double>(numFrames) / sampleRate;
            const double rate = std::min(0.4, 0.1 * a);
            positionRt_ += std::clamp(error, -rate * dt, rate * dt);
        } else if (strong || (isGlobal && conf >= 0.5)) {
            // Hard relocation: a large error is applied only for a strong
            // (high-confidence, low-ambiguity) match or a confident initial
            // global lock. An ambiguous large error is rejected (no false jump).
            positionRt_ += error;
            targetActive_.store(false, std::memory_order_relaxed);
        } else {
            // Large + ambiguous: reject the target (do not jump).
            targetActive_.store(false, std::memory_order_relaxed);
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
