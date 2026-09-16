#include "tests/host/test_main.h"
#include "beat/BeatTracker.h"

#include <array>
#include <cmath>
#include <limits>

using namespace temposcore;

static BeatObservation run(double tempo, double prior = 120.0, int seconds = 12,
                           int subdivision = 1, float scale = 1.0f) {
    BeatTracker tracker;
    tracker.configure(prior, 48000, 1024);
    const int frames = 48000 / 1024;
    const int period = static_cast<int>(std::lround(46.875 * 60.0 / tempo / subdivision));
    BeatObservation out;
    for (int n = 0; n < seconds * 46; ++n) {
        const bool hit = n % std::max(1, period) == 0;
        const float value = hit ? scale : 0.0f;
        out = tracker.processFlux({value, value * 0.8f, value * 0.5f}, hit ? 0.03f : 0.0f,
                                  static_cast<int64_t>(n) * frames);
    }
    return out;
}

static void test_120() { CHECK_NEAR(run(120).detectedBpm, 120.0, 3.0); }
static void test_90_prior_120() { CHECK_NEAR(run(90, 120).detectedBpm, 90.0, 3.0); }
static void test_eighths() { CHECK_NEAR(run(90, 120, 12, 2).detectedBpm, 90.0, 5.0); }
static void test_sixteenths() { CHECK_NEAR(run(120, 120, 12, 4).detectedBpm, 120.0, 5.0); }

static void test_tempo_change() {
    BeatTracker tracker;
    tracker.configure(120.0, 48000, 1024);
    BeatObservation out;
    for (int n = 0; n < 18 * 46; ++n) {
        const double tempo = n < 8 * 46 ? 120.0 : 90.0;
        const int period = static_cast<int>(std::lround(46.875 * 60.0 / tempo));
        const float hit = n % period == 0 ? 1.0f : 0.0f;
        out = tracker.processFlux({hit, hit, hit}, hit ? 0.03f : 0.0f, n * 1024);
    }
    CHECK_NEAR(out.detectedBpm, 90.0, 6.0);
}

static void test_silence() {
    const BeatObservation out = run(120, 120, 12, 1, 0.0f);
    CHECK(!out.tempoValid); CHECK_NEAR(out.detectedBpm, 0.0, 0.01);
}

static void test_amplitude_variation() {
    CHECK_NEAR(run(120, 120, 12, 1, 0.2f).detectedBpm, 120.0, 5.0);
    CHECK_NEAR(run(120, 120, 12, 1, 2.0f).detectedBpm, 120.0, 5.0);
}

static void test_delayed_lock_and_nonfinite_inputs() {
    BeatTracker t;
    t.configure(120.0, 48000, 1024);
    BeatObservation o;
    for (int n = 0; n < 3 * 46; ++n)
        o = t.processFlux({1.0f, 0.8f, 0.5f}, 0.03f, n * 1024);
    CHECK(o.detectedBpm == 0.0);
    for (int n = 3 * 46; n < 10 * 46; ++n) {
        const float hit = n % 23 == 0 ? 1.0f : 0.0f;
        o = t.processFlux({hit, hit, hit}, hit ? 0.03f : 0.0f, n * 1024);
    }
    CHECK(o.tempoValid);
    const float confidence = o.confidence;
    t.setExpectedBpm(std::numeric_limits<double>::quiet_NaN());
    CHECK(t.processFlux({NAN, 0.0f, 0.0f}, NAN, 1).confidence >= 0.0f);
    CHECK(confidence > 0.0f);
}

static void test_silence_clears_prior_lock() {
    BeatTracker t;
    t.configure(120.0, 48000, 1024);
    BeatObservation o;
    for (int n = 0; n < 10 * 46; ++n) {
        const float hit = n % 23 == 0 ? 1.0f : 0.0f;
        o = t.processFlux({hit, hit, hit}, hit ? 0.03f : 0.0f, n * 1024);
    }
    for (int n = 0; n < 10 * 46; ++n) o = t.processFlux({0, 0, 0}, 0.0f, n * 1024);
    CHECK(!o.tempoValid); CHECK_NEAR(o.detectedBpm, 0.0, 0.01);
}

REGISTER_TEST(test_120);
REGISTER_TEST(test_90_prior_120);
REGISTER_TEST(test_eighths);
REGISTER_TEST(test_sixteenths);
REGISTER_TEST(test_tempo_change);
REGISTER_TEST(test_silence);
REGISTER_TEST(test_amplitude_variation);
REGISTER_TEST(test_delayed_lock_and_nonfinite_inputs);
REGISTER_TEST(test_silence_clears_prior_lock);
