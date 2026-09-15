#include "tests/host/test_main.h"
#include "beat/BeatTracker.h"

#include <vector>

using namespace temposcore;

// Feed one block of `numFrames` constant-amplitude samples; return the observation.
static BeatObservation feed(BeatTracker& t, int32_t numFrames, float amp) {
    std::vector<float> block(static_cast<size_t>(numFrames), amp);
    return t.process(block.data(), numFrames, 1);
}

// Build up a running beat PLL (confidence > 0) at ~120 BPM, then verify that a
// non-resetting setExpectedBpm (a tempo-region crossing while the stream is
// running) preserves the PLL state instead of restarting it (gate-5 M1).
static void test_set_expected_bpm_preserves_pll() {
    constexpr int32_t sr = 48000;
    constexpr int32_t block = 4800; // 100 ms

    BeatTracker t;
    t.configure(120.0, sr);

    // Silence, then a loud block (onset 1), then enough silence to clear the
    // 75 ms refractory window, then a second loud block (onset 2 -> candidate
    // BPM + confidence bump).
    feed(t, block, 0.0f);
    feed(t, block, 0.5f); // onset 1
    for (int i = 0; i < 3; ++i) feed(t, block, 0.0f);
    const BeatObservation o2 = feed(t, block, 0.5f); // onset 2
    CHECK(o2.confidence > 0.0f); // PLL is running
    const float before = o2.confidence;

    // A tempo-region crossing while running: non-resetting update.
    t.setExpectedBpm(150.0);

    // One silent block: confidence should decay slightly (x0.9995), NOT reset to 0.
    const BeatObservation o3 = feed(t, block, 0.0f);
    CHECK(o3.confidence > 0.05f);          // still running (not reset)
    CHECK_NEAR(o3.confidence, before * 0.9995f, 0.01f); // decayed, not zeroed
}

// configure() still performs a full reset (pre-start path).
static void test_configure_resets() {
    constexpr int32_t sr = 48000;
    constexpr int32_t block = 4800;

    BeatTracker t;
    t.configure(120.0, sr);
    feed(t, block, 0.0f);
    feed(t, block, 0.5f);
    for (int i = 0; i < 3; ++i) feed(t, block, 0.0f);
    const BeatObservation o2 = feed(t, block, 0.5f);
    CHECK(o2.confidence > 0.0f);

    t.configure(120.0, sr); // full reset
    const BeatObservation o3 = feed(t, block, 0.0f);
    CHECK(o3.confidence < 0.01f); // reset to ~0
}

REGISTER_TEST(test_set_expected_bpm_preserves_pll);
REGISTER_TEST(test_configure_resets);
