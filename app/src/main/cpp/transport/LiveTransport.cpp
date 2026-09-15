#include "transport/LiveTransport.h"

#include <algorithm>

namespace temposcore {

void LiveTransport::configure(double expectedBpm, double startQuarterBeat) noexcept {
    expectedBpm = std::clamp(expectedBpm, 30.0, 300.0);
    requestedExpectedBpm_.store(expectedBpm, std::memory_order_relaxed);
    requestedPosition_.store(std::max(0.0, startQuarterBeat), std::memory_order_relaxed);
    positionGeneration_.fetch_add(1, std::memory_order_release);
    expectedBpmRt_ = expectedBpm;
    currentBpmRt_ = expectedBpm;
    positionRt_ = std::max(0.0, startQuarterBeat);
    publishedBpm_.store(currentBpmRt_, std::memory_order_relaxed);
    publishedPosition_.store(positionRt_, std::memory_order_relaxed);
}

void LiveTransport::resetPosition(double startQuarterBeat) noexcept {
    const double clamped = std::max(0.0, startQuarterBeat);
    requestedPosition_.store(clamped, std::memory_order_relaxed);
    publishedPosition_.store(clamped, std::memory_order_relaxed);
    positionGeneration_.fetch_add(1, std::memory_order_release);
}

void LiveTransport::setExpectedBpm(double expectedBpm) noexcept {
    const double clamped = std::clamp(expectedBpm, 30.0, 300.0);
    requestedExpectedBpm_.store(clamped, std::memory_order_relaxed);
    if (!running_.load(std::memory_order_relaxed)) {
        publishedBpm_.store(clamped, std::memory_order_relaxed);
    }
}

void LiveTransport::setRunning(bool running) noexcept {
    running_.store(running, std::memory_order_release);
}
void LiveTransport::submitPositionObservation(const PositionObservation&o) noexcept {
    if (!o.valid) return; // only a confident match sets a correction target
    targetPosition_.store(o.quarterBeatPosition,std::memory_order_relaxed);
    targetConfidence_.store(o.confidence,std::memory_order_relaxed);
    targetAmbiguity_.store(o.ambiguityMargin,std::memory_order_relaxed);
    targetGlobal_.store(o.globalMatch,std::memory_order_relaxed);
    targetActive_.store(true,std::memory_order_release);
}

void LiveTransport::processFrames(int32_t numFrames,
                                  int32_t sampleRate,
                                  const BeatObservation& observation) noexcept {
    if (numFrames <= 0 || sampleRate <= 0) return;

    expectedBpmRt_ = requestedExpectedBpm_.load(std::memory_order_relaxed);
    const uint64_t generation = positionGeneration_.load(std::memory_order_acquire);
    if (generation != appliedPositionGeneration_) {
        positionRt_ = requestedPosition_.load(std::memory_order_relaxed);
        appliedPositionGeneration_ = generation;
    }

    if (observation.tempoValid && observation.confidence > 0.08f) {
        // Separate detected BPM from transport BPM: the cursor must move smoothly.
        const double target = std::clamp(observation.detectedBpm,
                                         expectedBpmRt_ * 0.55,
                                         expectedBpmRt_ * 1.80);
        currentBpmRt_ = 0.985 * currentBpmRt_ + 0.015 * target;
    }

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
    if (observation.phaseValid && observation.confidence > 0.18f) {
        positionRt_ += observation.phaseCorrectionBeats;
    }
    positionRt_ = std::max(0.0, positionRt_);

    publishedBpm_.store(currentBpmRt_, std::memory_order_relaxed);
    publishedDetectedBpm_.store(observation.confidence > 0.03f ? observation.detectedBpm : 0.0,
                                std::memory_order_relaxed);
    publishedPosition_.store(positionRt_, std::memory_order_relaxed);
    publishedConfidence_.store(observation.confidence, std::memory_order_relaxed);
    publishedRms_.store(observation.rms, std::memory_order_relaxed);
}

TransportState LiveTransport::snapshot() const noexcept {
    TransportState s;
    s.transportBpm = publishedBpm_.load(std::memory_order_relaxed);
    s.detectedBpm = publishedDetectedBpm_.load(std::memory_order_relaxed);
    s.quarterBeatPosition = publishedPosition_.load(std::memory_order_relaxed);
    s.confidence = publishedConfidence_.load(std::memory_order_relaxed);
    s.rms = publishedRms_.load(std::memory_order_relaxed);
    s.running = running_.load(std::memory_order_acquire);
    return s;
}

} // namespace temposcore
