#include "audio/AudioAnalyzer.h"
#include "audio/SpscAudioRing.h"
#include "transport/LiveTransport.h"
#include "tests/host/test_main.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <thread>

using namespace temposcore;

namespace {
constexpr int kRate = 48000;
constexpr uint64_t kStream = 88;

template <typename Predicate>
bool waitFor(const Predicate& predicate) {
    for (int attempt = 0; attempt < 1000; ++attempt) {
        if (predicate()) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return predicate();
}
}

void test_analyzer_restarts_dsp_and_invalidates_match_after_captured_gaps() {
    SpscAudioRing ring(32768);
    LiveTransport transport;
    transport.configure(123.0, 0.0);
    transport.beginStreamEpoch(kStream);
    AudioAnalyzer analyzer(ring);
    analyzer.configureMatcher(nullptr, &transport);
    CHECK(analyzer.start(kRate, 123.0));

    uint64_t sourceFrame = 0;
    uint64_t continuity = 1;
    uint64_t sequence = 0;
    std::array<float, 1024> pcm{};
    auto feed = [&](uint64_t frames) {
        while (frames) {
            const uint32_t count = static_cast<uint32_t>(std::min<uint64_t>(pcm.size(), frames));
            const uint64_t generation = transport.processSourceFrames(
                static_cast<int32_t>(count), kRate, kStream, continuity, sourceFrame);
            const AudioSourceSpan span{kStream, continuity, generation, sourceFrame, count};
            CHECK(ring.writeStamped(pcm.data(), count, span) == count);
            sourceFrame += count;
            frames -= count;
        }
    };
    auto lockAtCurrentSource = [&] {
        PositionObservation observation;
        observation.quarterBeatPosition = transport.snapshot().quarterBeatPosition;
        observation.confidence = 0.95f;
        observation.ambiguityMargin = 0.3f;
        observation.valid = true;
        observation.state = PositionTrackingState::Locked;
        observation.resetGeneration = transport.positionGeneration();
        observation.streamEpoch = kStream;
        observation.continuityEpoch = continuity;
        observation.observationFrame = sourceFrame;
        observation.sequence = ++sequence;
        transport.submitPositionObservation(observation, 4.0);
        feed(1024);
        CHECK(transport.snapshot().positionConfidence > 0.9);
    };

    feed(12288);
    CHECK(waitFor([&] { return analyzer.diagnostics().featureCount >= 2; }));
    CHECK(waitFor([&] { return ring.available() == 0; }));

    for (const uint64_t dropoutFrames : {4800ULL, 24000ULL, 96000ULL}) {
        lockAtCurrentSource();
        CHECK(waitFor([&] { return ring.available() == 0; }));
        // Captured PCM advances transport/source time but these samples are
        // deliberately not written to analyzer ring (0.1/0.5/2.0 s loss).
        uint64_t remaining = dropoutFrames;
        while (remaining) {
            const uint32_t count = static_cast<uint32_t>(std::min<uint64_t>(1024, remaining));
            transport.processSourceFrames(static_cast<int32_t>(count), kRate, kStream, continuity, sourceFrame);
            sourceFrame += count;
            remaining -= count;
        }
        const double positionBeforeResume = transport.snapshot().quarterBeatPosition;
        ++continuity;
        const uint64_t expectedResetCount = analyzer.diagnostics().continuityResets + 1;
        feed(8192);
        CHECK(transport.snapshot().positionConfidence == 0.0);
        CHECK(transport.snapshot().positionStateCode == static_cast<int>(PositionTrackingState::Reacquiring));
        CHECK(transport.snapshot().quarterBeatPosition > positionBeforeResume);
        CHECK(transport.snapshot().quarterBeatPosition < positionBeforeResume + 0.5);
        CHECK(waitFor([&] { return analyzer.diagnostics().continuityResets >= expectedResetCount; }));
        CHECK(waitFor([&] { return ring.available() == 0; }));
        CHECK(analyzer.diagnostics().latestFeatureCenterFrame >=
              static_cast<int64_t>(sourceFrame - 8192));
    }
    analyzer.stop();
}

REGISTER_TEST(test_analyzer_restarts_dsp_and_invalidates_match_after_captured_gaps);
