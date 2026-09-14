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
