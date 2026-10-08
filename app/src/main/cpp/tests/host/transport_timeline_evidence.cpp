#include "transport/LiveTransport.h"

#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>

namespace {
constexpr int kRate = 48000;
constexpr int kBlock = 480;
constexpr int kDurationSeconds = 180;
constexpr double kTempo = 123.0;

void runScenario(std::ofstream& csv, bool injectMatches) {
    temposcore::LiveTransport transport;
    transport.configure(kTempo, 0.2); // known +0.2 beat initial phase offset
    transport.beginStreamEpoch(19);
    transport.enableDiagnostics(true);
    uint64_t sourceFrame = 0;
    uint64_t sequence = 0;
    temposcore::TransportDiagnosticRecord diagnostic;
    for (int callback = 0; callback < kDurationSeconds * kRate / kBlock; ++callback) {
        if (injectMatches && callback >= 25 && callback % 200 == 0) {
            const uint64_t observedFrame = sourceFrame - static_cast<uint64_t>(0.25 * kRate);
            temposcore::PositionObservation observation;
            observation.quarterBeatPosition = kTempo *
                (static_cast<double>(observedFrame) / kRate) / 60.0;
            observation.confidence = 0.95f;
            observation.ambiguityMargin = 0.3f;
            observation.valid = true;
            observation.state = temposcore::PositionTrackingState::Locked;
            observation.resetGeneration = transport.positionGeneration();
            observation.streamEpoch = 19;
            observation.continuityEpoch = 1;
            observation.observationFrame = observedFrame;
            observation.sequence = ++sequence;
            transport.submitPositionObservation(observation, 20.0);
        }
        transport.processSourceFrames(kBlock, kRate, 19, 1, sourceFrame);
        sourceFrame += kBlock;
        const double elapsed = static_cast<double>(sourceFrame) / kRate;
        const auto state = transport.snapshot();
        while (transport.popDiagnostic(diagnostic)) {}
        if (callback % 10 == 0) {
            const double groundTruth = kTempo * elapsed / 60.0;
            const double baseBpm = 60.0 * diagnostic.baseDeltaBeats / (kBlock / static_cast<double>(kRate));
            const double effectiveBpm = 60.0 * diagnostic.actualDeltaBeats / (kBlock / static_cast<double>(kRate));
            csv << (injectMatches ? "history_match_250ms" : "baseline_no_match") << ','
                << std::fixed << std::setprecision(6) << elapsed << ',' << sourceFrame << ','
                << state.quarterBeatPosition << ',' << groundTruth << ','
                << state.quarterBeatPosition - groundTruth << ',' << baseBpm << ',' << effectiveBpm << ','
                << diagnostic.baseDeltaBeats << ',' << diagnostic.correctionDeltaBeats << ','
                << diagnostic.actualDeltaBeats << ',' << diagnostic.relocationDeltaBeats << ','
                << diagnostic.observationSequence << ','
                << diagnostic.observationAgeFrames << ',' << diagnostic.rawObservationError << ','
                << diagnostic.projectedCorrectionError << ',' << diagnostic.rejectionCode << '\n';
        }
    }
}
}

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: temposcore_transport_timeline_evidence OUTPUT.csv\n";
        return 2;
    }
    std::ofstream csv(argv[1]);
    if (!csv) return 2;
    csv << "scenario,elapsed_s,source_frame,position_beat,ground_truth_beat,position_error_beat,"
           "base_bpm,effective_cursor_bpm,base_delta_beat,correction_delta_beat,actual_delta_beat,"
           "relocation_delta_beat,observation_sequence,observation_age_frames,raw_observation_error,"
           "projected_error,rejection_code\n";
    runScenario(csv, false);
    runScenario(csv, true);
    return csv ? 0 : 1;
}
