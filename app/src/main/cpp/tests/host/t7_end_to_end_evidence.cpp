#include "audio/AudioAnalyzer.h"
#include "audio/SpscAudioRing.h"
#include "position/ScoreReference.h"
#include "transport/LiveTransport.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <thread>
#include <vector>

namespace {
constexpr int kBlockFrames = 480; // 10 ms callback partition.
constexpr int kBlocksPerBatch = 10; // bounded 100 ms ring backlog, then await analyzer.
constexpr int64_t kPpq = 480;
constexpr int kInitialScoreBeat = 8; // audio/MIDI ground truth offset; transport starts at zero.
constexpr int kScoreBeats = 384;
constexpr double kMicrosecondsPerQuarter = 487805.0;
constexpr double kTempo = 60000000.0 / kMicrosecondsPerQuarter;
constexpr double kPi = 3.14159265358979323846;
constexpr uint64_t kStreamEpoch = 909;

std::vector<int> makePitchClasses() {
    std::vector<int> pitches(kScoreBeats);
    uint32_t state = 0x13579bdfU;
    int previous = -1;
    for (int beat = 0; beat < kScoreBeats; ++beat) {
        int pc = 0;
        do {
            state = state * 1664525U + 1013904223U;
            pc = static_cast<int>((state >> 16) % 12);
        } while (pc == previous);
        pitches[beat] = pc;
        previous = pc;
    }
    return pitches;
}

temposcore::MidiData makeMidi(const std::vector<int>& pitchClasses) {
    temposcore::MidiData midi;
    midi.ppq = static_cast<int>(kPpq);
    midi.totalTicks = static_cast<int64_t>(kScoreBeats) * kPpq;
    midi.tempos.push_back({0, static_cast<int>(kMicrosecondsPerQuarter)});
    for (int beat = 0; beat < kScoreBeats; ++beat) {
        const int pitch = 60 + pitchClasses[beat];
        const int64_t start = static_cast<int64_t>(beat) * kPpq;
        midi.notes.push_back({0, pitch, 96, start, start + 360});
    }
    return midi;
}

class PcmSource final {
public:
    PcmSource(int sampleRate, const std::vector<int>& pitchClasses)
        : sampleRate_(sampleRate), pitchClasses_(pitchClasses), quarterSeconds_(60.0 / kTempo) {
        for (int pc = 0; pc < 12; ++pc) {
            const double frequency = 440.0 * std::pow(2.0, (60 + pc - 69) / 12.0);
            for (size_t i = 0; i < wave_[pc].size(); ++i) {
                const double phase = 2.0 * kPi * i / wave_[pc].size();
                wave_[pc][i] = static_cast<float>(std::sin(phase) +
                    0.32 * std::sin(2.0 * phase) + 0.14 * std::sin(3.0 * phase));
            }
            phaseIncrement_[pc] = wave_[pc].size() * frequency / sampleRate_;
        }
    }

    void fill(uint64_t firstFrame, float* out, size_t count) const {
        for (size_t i = 0; i < count; ++i) {
            const uint64_t frame = firstFrame + i;
            const double scoreBeat = kInitialScoreBeat +
                (static_cast<double>(frame) / sampleRate_) * kTempo / 60.0;
            int beatIndex = static_cast<int>(std::floor(scoreBeat));
            beatIndex = std::clamp(beatIndex, 0, static_cast<int>(pitchClasses_.size()) - 1);
            const double noteSeconds = (scoreBeat - std::floor(scoreBeat)) * quarterSeconds_;
            const double noteDuration = 0.75 * quarterSeconds_;
            const double attack = std::min(1.0, noteSeconds / 0.004);
            const double release = noteSeconds < noteDuration ? 1.0 :
                std::max(0.0, 1.0 - (noteSeconds - noteDuration) / 0.035);
            const double envelope = attack * release * (0.72 + 0.28 * std::exp(-2.0 * noteSeconds));
            const int pc = pitchClasses_[beatIndex];
            const double notePhase = std::fmod(noteSeconds * sampleRate_ * phaseIncrement_[pc], wave_[pc].size());
            const size_t waveIndex = static_cast<size_t>(notePhase);
            const double click = noteSeconds < 0.04
                ? 0.12 * std::exp(-noteSeconds * 90.0) * std::sin(2.0 * kPi * 1700.0 * noteSeconds)
                : 0.0;
            out[i] = static_cast<float>(0.19 * envelope * wave_[pc][waveIndex] + click);
        }
    }

private:
    int sampleRate_;
    const std::vector<int>& pitchClasses_;
    double quarterSeconds_;
    std::array<std::array<float, 2048>, 12> wave_{};
    std::array<double, 12> phaseIncrement_{};
};

struct ErrorSample { double elapsed; double error; };

double percentile(std::vector<double> values, double p) {
    if (values.empty()) return 0.0;
    std::sort(values.begin(), values.end());
    const size_t index = static_cast<size_t>(p * static_cast<double>(values.size() - 1));
    return values[index];
}

bool waitUntilDrained(temposcore::SpscAudioRing& ring, uint64_t targetProcessedFrame,
                      temposcore::AudioAnalyzer& analyzer) {
    for (int i = 0; i < 3000; ++i) {
        const auto d = analyzer.diagnostics();
        if (ring.available() == 0 && d.latestProcessedFrame >= targetProcessedFrame) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return false;
}

int run(int sampleRate, int durationSeconds, const std::string& csvPath) {
    const auto pitchClasses = makePitchClasses();
    auto midi = makeMidi(pitchClasses);
    temposcore::ScoreReference reference;
    if (!temposcore::buildScoreReference(midi, reference)) return 2;

    temposcore::SpscAudioRing ring(200000);
    temposcore::LiveTransport transport;
    transport.configure(kTempo, 0.0); // Deliberately not seeded with truth beat 8.
    transport.beginStreamEpoch(kStreamEpoch);
    transport.setRunning(true);
    transport.enableDiagnostics(true);
    temposcore::PositionMatcher matcher(reference);
    matcher.begin();
    temposcore::AudioAnalyzer analyzer(ring);
    analyzer.configureMatcher(&matcher, &transport);
    if (!analyzer.start(sampleRate, kTempo)) return 2;

    PcmSource source(sampleRate, pitchClasses);
    std::array<float, kBlockFrames> pcm{};
    std::ofstream csv(csvPath);
    if (!csv) { analyzer.stop(); return 2; }
    csv << "sample_rate,elapsed_s,source_frame,ground_truth_beat,transport_beat,position_error_beat,"
           "position_state,position_confidence,matched_beat,base_bpm,effective_cursor_bpm,base_delta,"
           "correction_delta,actual_delta,relocation_delta,observation_sequence,observation_age_frames,raw_error,"
           "projected_error,rejection_code,feature_count,feature_rate_hz,matcher_runs,match_compute_us,"
           "history_misses,history_overflows,backlog_frames,dropped_frames\n";

    std::vector<ErrorSample> postLock;
    std::vector<double> absoluteErrors;
    uint64_t sourceFrame = 0;
    uint64_t diagnosticRows = 0;
    uint64_t lockFrame = 0;
    bool acquired = false;
    bool noDrops = true;
    uint64_t sourceEndFrame = 0;
    int sourceCallbacks = 0;
    while (sourceFrame < static_cast<uint64_t>(durationSeconds) * sampleRate) {
        sourceCallbacks = 0;
        while (sourceCallbacks < kBlocksPerBatch &&
               sourceFrame < static_cast<uint64_t>(durationSeconds) * sampleRate) {
            const uint32_t count = static_cast<uint32_t>(std::min<uint64_t>(
                kBlockFrames, static_cast<uint64_t>(durationSeconds) * sampleRate - sourceFrame));
            source.fill(sourceFrame, pcm.data(), count);
            const uint64_t positionGeneration = transport.processSourceFrames(
                static_cast<int32_t>(count), sampleRate, kStreamEpoch, 1, sourceFrame);
            const temposcore::AudioSourceSpan span{
                kStreamEpoch, 1, positionGeneration, sourceFrame, count,
            };
            if (ring.writeStamped(pcm.data(), count, span) != count) noDrops = false;
            sourceFrame += count;
            sourceEndFrame = sourceFrame;
            const auto state = transport.snapshot();
            const auto analyzerDiagnostics = analyzer.diagnostics();
            const double elapsed = static_cast<double>(sourceEndFrame) / sampleRate;
            const double truth = kInitialScoreBeat + kTempo * elapsed / 60.0;
            const double error = state.quarterBeatPosition - truth;
            const bool locked = state.positionStateCode ==
                static_cast<int>(temposcore::PositionTrackingState::Locked);
            if (locked && !acquired) {
                acquired = true;
                lockFrame = sourceEndFrame;
            }
            if (acquired && locked) {
                postLock.push_back({elapsed, error});
                absoluteErrors.push_back(std::abs(error));
            }
            temposcore::TransportDiagnosticRecord diagnostic;
            while (transport.popDiagnostic(diagnostic)) {
                const double blockSeconds = static_cast<double>(count) / sampleRate;
                const double baseBpm = blockSeconds > 0 ? 60.0 * diagnostic.baseDeltaBeats / blockSeconds : 0.0;
                const double effectiveBpm = blockSeconds > 0 ? 60.0 * diagnostic.actualDeltaBeats / blockSeconds : 0.0;
                const double recordElapsed = static_cast<double>(diagnostic.sourceEndFrame) / sampleRate;
                const double recordTruth = kInitialScoreBeat + kTempo * recordElapsed / 60.0;
                csv << sampleRate << ',' << recordElapsed << ',' << diagnostic.sourceEndFrame << ','
                    << recordTruth << ',' << state.quarterBeatPosition << ',' << error << ','
                    << state.positionStateCode << ',' << state.positionConfidence << ','
                    << state.matchedQuarterBeatPosition << ',' << baseBpm << ',' << effectiveBpm << ','
                    << diagnostic.baseDeltaBeats << ',' << diagnostic.correctionDeltaBeats << ','
                    << diagnostic.actualDeltaBeats << ',' << diagnostic.relocationDeltaBeats << ','
                    << diagnostic.observationSequence << ',' << diagnostic.observationAgeFrames << ','
                    << diagnostic.rawObservationError << ',' << diagnostic.projectedCorrectionError << ','
                    << diagnostic.rejectionCode << ',' << analyzerDiagnostics.featureCount << ','
                    << analyzerDiagnostics.featureRateHz << ',' << analyzerDiagnostics.matcherRunCount << ','
                    << analyzerDiagnostics.lastMatcherComputeMicros << ','
                    << analyzerDiagnostics.historyLookupMisses << ',' << analyzerDiagnostics.historyOverflowEvents << ','
                    << ring.available() << ','
                    << ring.droppedSamples() << '\n';
                ++diagnosticRows;
            }
            ++sourceCallbacks;
        }
        if (!waitUntilDrained(ring, sourceEndFrame, analyzer)) {
            noDrops = false;
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1)); // Let worker drain history SPSC.
    }
    analyzer.stop();

    const double slope = [&] {
        if (postLock.size() < 2) return 0.0;
        double meanX = 0.0, meanY = 0.0;
        for (const auto& sample : postLock) { meanX += sample.elapsed; meanY += sample.error; }
        meanX /= postLock.size(); meanY /= postLock.size();
        double numerator = 0.0, denominator = 0.0;
        for (const auto& sample : postLock) {
            numerator += (sample.elapsed - meanX) * (sample.error - meanY);
            denominator += (sample.elapsed - meanX) * (sample.elapsed - meanX);
        }
        return denominator > 0 ? numerator / denominator : 0.0;
    }();
    const double p50 = percentile(absoluteErrors, 0.50);
    const double p95 = percentile(absoluteErrors, 0.95);
    const double maximum = absoluteErrors.empty() ? 0.0 : *std::max_element(absoluteErrors.begin(), absoluteErrors.end());
    const double lockSeconds = acquired ? static_cast<double>(lockFrame) / sampleRate : -1.0;
    const double lockedFraction = static_cast<double>(postLock.size()) /
        std::max(1.0, static_cast<double>(durationSeconds) * sampleRate / kBlockFrames);
    std::cerr << "T7 sample_rate=" << sampleRate << " acquired=" << acquired
              << " lock_s=" << lockSeconds << " post_lock_points=" << postLock.size()
              << " p50_abs_error=" << p50 << " p95_abs_error=" << p95
              << " max_abs_error=" << maximum << " slope_beats_per_s=" << slope
              << " locked_fraction=" << lockedFraction << " ring_no_drop=" << noDrops
              << " diag_rows=" << diagnosticRows
              << " matcher_runs=" << analyzer.diagnostics().matcherRunCount
              << " history_misses=" << analyzer.diagnostics().historyLookupMisses
              << " history_overflows=" << transport.historyOverflowCount()
              << " diag_queue_drops=" << transport.droppedDiagnosticRecords() << "\n";
    csv.flush();
    // Exit success means harness completed; stats in stderr/docs decide PASS/FAIL.
    return sourceEndFrame >= static_cast<uint64_t>(durationSeconds) * sampleRate ? 0 : 1;
}
}

int main(int argc, char** argv) {
    if (argc != 3 && argc != 4) {
        std::cerr << "usage: t7_end_to_end_evidence SAMPLE_RATE OUTPUT.csv [DURATION_SECONDS]\n";
        return 2;
    }
    const int duration = argc == 4 ? std::stoi(argv[3]) : 180;
    return run(std::stoi(argv[1]), duration, argv[2]);
}
