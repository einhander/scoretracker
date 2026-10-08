#include "audio/FeatureGrid.h"
#include "position/FeatureRing.h"
#include "tests/host/test_main.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace {
struct GridResult {
    std::vector<int64_t> centers;
    uint64_t skipped = 0;
};

GridResult runGrid(int sampleRate, int durationSeconds, int hop = 1024) {
    constexpr int64_t firstCenter = 2048;
    temposcore::FeatureGrid grid(sampleRate, firstCenter);
    GridResult result;
    for (int64_t center = firstCenter; center < static_cast<int64_t>(durationSeconds) * sampleRate; center += hop) {
        uint64_t skipped = 0;
        if (grid.due(center, &skipped)) {
            result.centers.push_back(center);
            result.skipped += skipped;
        }
    }
    return result;
}

size_t runLegacyGrid(int sampleRate, int durationSeconds, int hop = 1024) {
    int64_t deadline = 2048;
    size_t count = 0;
    for (int64_t center = 2048; center < static_cast<int64_t>(durationSeconds) * sampleRate; center += hop) {
        if (center < deadline) continue;
        ++count;
        deadline = center + sampleRate / 10;
    }
    return count;
}
}

void test_feature_grid_timing() {
    constexpr int duration = 180;
    constexpr int hop = 1024;
    for (const int rate : {44100, 48000}) {
        const GridResult result = runGrid(rate, duration, hop);
        CHECK(result.centers.size() >= 1799 && result.centers.size() <= 1801);
        CHECK(result.skipped == 0);
        for (size_t i = 1; i < result.centers.size(); ++i) {
            CHECK(result.centers[i] > result.centers[i - 1]);
            const int64_t idealTimesTen = 2048 * 10LL + static_cast<int64_t>(i) * rate;
            const int64_t timestampErrorTimesTen = result.centers[i] * 10 - idealTimesTen;
            CHECK(timestampErrorTimesTen >= 0 && timestampErrorTimesTen <= hop * 10LL);
        }
        const double spanSeconds = static_cast<double>(result.centers.back() - result.centers.front()) / rate;
        const double rateHz = static_cast<double>(result.centers.size() - 1) / spanSeconds;
        CHECK(std::abs(rateHz - 10.0) < 0.01);

        // Red/green guard: the exact old center-relative deadline model must
        // remain observably wrong, so reverting to it fails the 10 Hz bound.
        const size_t legacyCount = runLegacyGrid(rate, duration, hop);
        CHECK(legacyCount < 1750);
        CHECK(std::abs(static_cast<double>(legacyCount - 1) / spanSeconds - 10.0) > 0.5);

        temposcore::FeatureRing ring;
        ring.setSampleRate(rate);
        for (const int64_t center : result.centers) {
            temposcore::AudioFeatureFrame frame;
            frame.centerAudioFrame = center;
            frame.valid = true;
            ring.push(frame);
        }
        CHECK(ring.size() == temposcore::FeatureRing::Capacity);
        CHECK(ring.durationSeconds() >= 19.7 && ring.durationSeconds() <= 20.1);
        CHECK(ring.durationSeconds() < static_cast<double>(ring.size()) / 9.3);
    }

    // A delayed worker presents only its latest available STFT center. Scheduler
    // reports missed deadlines once, never repeats one center for catch-up.
    temposcore::FeatureGrid delayed(48000, 2048);
    uint64_t skipped = 0;
    CHECK(delayed.due(2048, &skipped));
    CHECK(skipped == 0);
    CHECK(delayed.due(48000 * 3 + 2048, &skipped));
    CHECK(skipped >= 28 && skipped <= 30);
    CHECK(!delayed.due(48000 * 3 + 2048 + 1024, &skipped));

    delayed.reset(44100, 2048); // STFT recreation/reset restarts its local frame axis.
    CHECK(delayed.due(2048, &skipped));
    CHECK(!delayed.due(2048 + 4096, &skipped));
    CHECK(delayed.due(2048 + 5120, &skipped));

    // Verify the worker's warm-up and ~2 s cadence arithmetic against actual
    // selected feature centers (not wall-clock calls or duplicated frames).
    for (const int rate : {44100, 48000}) {
        const auto result = runGrid(rate, 30);
        size_t fresh = 0;
        temposcore::FeatureMatcherCadence cadence;
        std::vector<int64_t> matcherIntervals;
        for (const int64_t center : result.centers) {
            ++fresh;
            if (cadence.due(center, rate, fresh)) {
                matcherIntervals.push_back(cadence.intervalFrames(center));
                cadence.markRun(center);
            }
        }
        CHECK(!matcherIntervals.empty());
        CHECK(matcherIntervals.front() >= 2LL * rate);
        CHECK(matcherIntervals.front() < 2.2 * rate);
        for (size_t i = 1; i < matcherIntervals.size(); ++i) {
            CHECK(matcherIntervals[i] >= 2LL * rate);
            CHECK(matcherIntervals[i] < 2.2 * rate);
        }
    }
}
REGISTER_TEST(test_feature_grid_timing);
