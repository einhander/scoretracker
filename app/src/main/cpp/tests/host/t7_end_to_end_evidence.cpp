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

size_t referenceIndexAtSeconds(const temposcore::ScoreReference& reference, double seconds) {
    const auto& frames = reference.frames();
    auto it = std::lower_bound(frames.begin(), frames.end(), seconds,
        [](const temposcore::ScoreFeatureFrame& frame, double time) { return frame.nominalSeconds < time; });
    if (it == frames.begin()) return 0;
    if (it == frames.end()) return frames.size() - 1;
    const auto prev = it - 1;
    return static_cast<size_t>((seconds - prev->nominalSeconds <= it->nominalSeconds - seconds)
        ? (prev - frames.begin()) : (it - frames.begin()));
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
    PcmSource(int sampleRate, const std::vector<int>& pitchClasses, bool pureTone = false, bool clickEnabled = true)
        : sampleRate_(sampleRate), pitchClasses_(pitchClasses), quarterSeconds_(60.0 / kTempo),
          pureTone_(pureTone), clickEnabled_(clickEnabled) {
        for (int pc = 0; pc < 12; ++pc) {
            const double frequency = 440.0 * std::pow(2.0, (60 + pc - 69) / 12.0);
            for (size_t i = 0; i < wave_[pc].size(); ++i) {
                const double phase = 2.0 * kPi * i / wave_[pc].size();
                wave_[pc][i] = static_cast<float>(std::sin(phase) +
                    (pureTone_ ? 0.0 : 0.32 * std::sin(2.0 * phase) + 0.14 * std::sin(3.0 * phase)));
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
            const double click = clickEnabled_ && noteSeconds < 0.04
                ? 0.12 * std::exp(-noteSeconds * 90.0) * std::sin(2.0 * kPi * 1700.0 * noteSeconds)
                : 0.0;
            out[i] = static_cast<float>(0.19 * envelope * wave_[pc][waveIndex] + click);
        }
    }

private:
    int sampleRate_;
    const std::vector<int>& pitchClasses_;
    double quarterSeconds_;
    bool pureTone_;
    bool clickEnabled_;
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

int run(int sampleRate, int durationSeconds, const std::string& csvPath,
        bool pureTone = false, bool clickEnabled = true) {
    const auto pitchClasses = makePitchClasses();
    auto midi = makeMidi(pitchClasses);
    temposcore::ScoreReference reference;
    if (!temposcore::buildScoreReference(midi, reference)) return 2;

    // Fixture ablation diagnostic only: compare production-centered live
    // features with nearest reference nominal time. Score position starts at 0,
    // while source plays score beat 8 at t=0; mapping must include that offset.
    {
        temposcore::Stft alignedStft(sampleRate);
        temposcore::ChromaExtractor alignedChroma(sampleRate);
        PcmSource alignedSource(sampleRate, pitchClasses, pureTone, clickEnabled);
        std::array<float, 1024> streamBlock{};
        double sumDistance = 0.0;
        size_t validCenters = 0;
        size_t count = 0;
        uint64_t alignedFrame = 0;
        while (alignedFrame < static_cast<uint64_t>(durationSeconds) * sampleRate) {
            const size_t chunk = static_cast<size_t>(std::min<uint64_t>(streamBlock.size(),
                static_cast<uint64_t>(durationSeconds) * sampleRate - alignedFrame));
            alignedSource.fill(alignedFrame, streamBlock.data(), chunk);
            alignedFrame += chunk;
            if (!alignedStft.process(streamBlock.data(), chunk)) continue;
            const uint64_t center = static_cast<uint64_t>(
                (alignedStft.frameIndex() - 1) * alignedStft.hop() + alignedStft.fftSize() / 2);
            const double truthBeat = kInitialScoreBeat + static_cast<double>(center) / sampleRate * kTempo / 60.0;
            const double truthSeconds = truthBeat * 60.0 / kTempo;
            const size_t refIndex = referenceIndexAtSeconds(reference, truthSeconds);
            const double mappedBeat = reference.frames()[refIndex].quarterBeatPosition;
            if (center < static_cast<uint64_t>(sampleRate / 50)) {
                if (std::abs(mappedBeat - kInitialScoreBeat) > 0.11 ||
                    std::abs(reference.frames()[refIndex].nominalSeconds -
                             kInitialScoreBeat * 60.0 / kTempo) > 0.1) return 3;
            }
            std::array<float, 12> chroma{};
            alignedChroma.extract(alignedStft.magnitude(), chroma);
            temposcore::AudioFeatureFrame live;
            live.chroma = chroma;
            live.valid = std::any_of(chroma.begin(), chroma.end(), [](float value) { return value > 0.0f; });
            if (live.valid) ++validCenters;
            sumDistance += temposcore::DtwMatcher::frameDistance(live, reference.frames()[refIndex]);
            ++count;
        }
        std::cerr << "aligned_chroma rate=" << sampleRate << " pure=" << pureTone
                  << " click=" << clickEnabled << " mean_frame_distance="
                  << (count ? sumDistance / count : 0.0) << " valid_fraction="
                  << (count ? static_cast<double>(validCenters) / count : 0.0)
                  << " mapping_tolerance_s=" << 0.1 << " first_reference_beat="
                  << reference.frames()[referenceIndexAtSeconds(reference, kInitialScoreBeat * 60.0 / kTempo)].quarterBeatPosition
                  << " samples=" << count << "\n";
    }

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

    PcmSource source(sampleRate, pitchClasses, pureTone, clickEnabled);
    std::array<float, kBlockFrames> pcm{};
    std::ofstream csv(csvPath);
    if (!csv) { analyzer.stop(); return 2; }
    csv << "sample_rate,elapsed_s,source_frame,ground_truth_beat,transport_beat,position_error_beat,"
           "position_state,position_confidence,matched_beat,match_truth_error_at_observation,"
           "base_bpm,effective_cursor_bpm,base_delta,"
           "correction_delta,actual_delta,relocation_delta,observation_sequence,observation_age_frames,raw_error,"
            "projected_error,rejection_code,feature_count,feature_rate_hz,matcher_runs,match_compute_us,"
            "detected_bpm,raw_detected_bpm,selected_lag,beat_confidence,dtw_best_cost,dtw_second_cost,"
           "dtw_best_beat,dtw_second_beat,dtw_live_first_frame,dtw_live_last_frame,"
           "dtw_valid_frame_fraction,feature_center_s,processed_time_s,"
           "history_misses,history_overflows,backlog_frames,dropped_frames\n";

    std::vector<ErrorSample> postLock;
    std::vector<double> absoluteErrors;
    uint64_t sourceFrame = 0;
    uint64_t diagnosticRows = 0;
    uint64_t lockFrame = 0;
    uint64_t firstLockSourceFrame = 0;
    double baseBpmSum = 0.0;
    double effectiveBpmSum = 0.0;
    uint64_t speedCount = 0;
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
                firstLockSourceFrame = sourceEndFrame;
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
                baseBpmSum += baseBpm;
                effectiveBpmSum += effectiveBpm;
                ++speedCount;
                const double recordElapsed = static_cast<double>(diagnostic.sourceEndFrame) / sampleRate;
                const double recordTruth = kInitialScoreBeat + kTempo * recordElapsed / 60.0;
                csv << sampleRate << ',' << recordElapsed << ',' << diagnostic.sourceEndFrame << ','
                    << recordTruth << ',' << state.quarterBeatPosition << ',' << error << ','
                    << state.positionStateCode << ',' << state.positionConfidence << ','
                    << state.matchedQuarterBeatPosition << ','
                    << ((analyzerDiagnostics.dtwLiveLastFrame > 0 &&
                         analyzerDiagnostics.dtwLiveLastFrame <= sourceEndFrame)
                        ? (analyzerDiagnostics.dtwBestQuarterBeat - kInitialScoreBeat -
                           kTempo * static_cast<double>(analyzerDiagnostics.dtwLiveLastFrame) / sampleRate / 60.0)
                        : 0.0) << ',' << baseBpm << ',' << effectiveBpm << ','
                    << diagnostic.baseDeltaBeats << ',' << diagnostic.correctionDeltaBeats << ','
                    << diagnostic.actualDeltaBeats << ',' << diagnostic.relocationDeltaBeats << ','
                    << diagnostic.observationSequence << ',' << diagnostic.observationAgeFrames << ','
                    << diagnostic.rawObservationError << ',' << diagnostic.projectedCorrectionError << ','
                    << diagnostic.rejectionCode << ',' << analyzerDiagnostics.featureCount << ','
                    << analyzerDiagnostics.featureRateHz << ',' << analyzerDiagnostics.matcherRunCount << ','
                    << analyzerDiagnostics.lastMatcherComputeMicros << ','
                    << analyzerDiagnostics.detectedBpm << ',' << analyzerDiagnostics.rawDetectedBpm << ','
                    << analyzerDiagnostics.selectedTempoLag << ',' << analyzerDiagnostics.beatConfidence << ','
                    << analyzerDiagnostics.dtwBestCost << ',' << analyzerDiagnostics.dtwSecondCost << ','
                    << (analyzerDiagnostics.dtwBestQuarterBeat - kInitialScoreBeat) << ','
                    << (analyzerDiagnostics.dtwSecondQuarterBeat - kInitialScoreBeat) << ','
                    << analyzerDiagnostics.dtwLiveFirstFrame << ',' << analyzerDiagnostics.dtwLiveLastFrame << ','
                    << analyzerDiagnostics.dtwValidFrameFraction << ','
                    << static_cast<double>(analyzerDiagnostics.latestFeatureCenterFrame) / sampleRate << ','
                    << static_cast<double>(analyzerDiagnostics.latestProcessedFrame) / sampleRate << ','
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
              << " first_lock_source_frame=" << firstLockSourceFrame
              << " p50_abs_error=" << p50 << " p95_abs_error=" << p95
             << " max_abs_error=" << maximum << " slope_beats_per_s=" << slope
              << " locked_fraction=" << lockedFraction << " ring_no_drop=" << noDrops
              << " feature_count=" << analyzer.diagnostics().featureCount
              << " feature_rate_hz=" << analyzer.diagnostics().featureRateHz
              << " first_live_frame=" << analyzer.diagnostics().dtwLiveFirstFrame
              << " last_live_frame=" << analyzer.diagnostics().dtwLiveLastFrame
              << " diag_rows=" << diagnosticRows
              << " matcher_runs=" << analyzer.diagnostics().matcherRunCount
              << " history_misses=" << analyzer.diagnostics().historyLookupMisses
              << " history_overflows=" << transport.historyOverflowCount()
              << " mean_base_bpm=" << (speedCount ? baseBpmSum / speedCount : 0.0)
              << " mean_effective_bpm=" << (speedCount ? effectiveBpmSum / speedCount : 0.0)
              << " diag_queue_drops=" << transport.droppedDiagnosticRecords() << "\n";
    csv.flush();
    // Exit success means harness completed; stats in stderr/docs decide PASS/FAIL.
    return sourceEndFrame >= static_cast<uint64_t>(durationSeconds) * sampleRate ? 0 : 1;
}
}

int main(int argc, char** argv) {
    if (argc < 3 || argc > 6) {
        std::cerr << "usage: t7_end_to_end_evidence SAMPLE_RATE OUTPUT.csv [DURATION_SECONDS] [harmonic|pure] [click|noclick]\n";
        return 2;
    }
    const int duration = argc >= 4 ? std::stoi(argv[3]) : 180;
    const bool pureTone = argc >= 5 && std::string(argv[4]) == "pure";
    const bool clickEnabled = argc < 6 || std::string(argv[5]) != "noclick";
    return run(std::stoi(argv[1]), duration, argv[2], pureTone, clickEnabled);
}
