#include "position/PositionMatcher.h"
#include "position/ScoreReference.h"
#include "transport/LiveTransport.h"
#include "tests/host/test_main.h"
#include <cmath>
using namespace temposcore;

// A reference with a UNIQUE (non-repeated) opening section so a strong unique
// match is possible. 8 distinct pitch classes, no repeats, 50 s (500 frames).
static ScoreReference makeUniqueRef() {
    MidiData m;
    m.ppq = 10;
    m.totalTicks = 1000;
    m.notes = {
        {0, 60, 100, 0, 100}, {0, 64, 100, 120, 220}, {0, 67, 100, 240, 340}, {0, 62, 100, 360, 460},
        {0, 65, 100, 480, 580}, {0, 69, 100, 600, 700}, {0, 71, 100, 720, 820}, {0, 74, 100, 840, 1000},
    };
    ScoreReference r;
    CHECK(buildScoreReference(m, r));
    return r;
}
// A reference with a CLEAN repeated section: the same 4-note progression (C E G D)
// appears twice (frames 0-59 and 120-179), separated by different material (frames 60-119).
// 6 s (60 frame) sections at 10 Hz, 18 s total (180 frames).
static ScoreReference makeRepeatRef() {
    MidiData m;
    m.ppq = 10;
    m.totalTicks = 360;
    m.notes = {
        // A (ticks 0-120): C E G D, 30 ticks each
        {0, 60, 100, 0, 30}, {0, 64, 100, 30, 60}, {0, 67, 100, 60, 90}, {0, 62, 100, 90, 120},
        // B (ticks 120-240): F A B D#, 30 ticks each
        {0, 65, 100, 120, 150}, {0, 69, 100, 150, 180}, {0, 71, 100, 180, 210}, {0, 74, 100, 210, 240},
        // A again (ticks 240-360): C E G D, 30 ticks each
        {0, 60, 100, 240, 270}, {0, 64, 100, 270, 300}, {0, 67, 100, 300, 330}, {0, 62, 100, 330, 360},
    };
    ScoreReference r;
    CHECK(buildScoreReference(m, r));
    return r;
}
static FeatureRing makeLiveFrom(const ScoreReference& r, size_t start, size_t count) {
    FeatureRing q;
    for (size_t i = start; i < start + count && i < r.frames().size(); ++i) {
        AudioFeatureFrame f;
        f.valid = true;
        f.chroma = r.frames()[i].chroma;
        f.onset = r.frames()[i].onset;
        q.push(f);
    }
    return q;
}
static FeatureRing makeSilent(size_t count) {
    FeatureRing q;
    for (size_t i = 0; i < count; ++i) q.push(AudioFeatureFrame{});
    return q;
}
static BeatObservation silentBeat() {
    BeatObservation b;
    b.tempoValid = false;
    b.phaseValid = false;
    b.confidence = 0.0f;
    b.rms = 0.0f;
    b.detectedBpm = 0.0;
    b.phaseCorrectionBeats = 0.0;
    return b;
}

// (a) Acquiring -> Locked on a strong unique match (60-frame window, full fill).
void test_matcher_acquire_lock() {
    auto r = makeUniqueRef();
    PositionMatcher m(r);
    m.begin();
    auto q = makeLiveFrom(r, 0, 60);
    auto o1 = m.update(q, 0.0);
    auto o2 = m.update(q, 0.0);
    CHECK(m.state() == PositionTrackingState::Locked);
    CHECK(o2.confidence >= 0.8f);
}

// (b) Locked -> Weak -> Reacquiring on 3 consecutive weak (silent) observations.
void test_matcher_degrade_reacquire() {
    auto r = makeUniqueRef();
    PositionMatcher m(r);
    m.begin();
    auto q = makeLiveFrom(r, 0, 60);
    m.update(q, 0.0);
    m.update(q, 0.0);
    CHECK(m.state() == PositionTrackingState::Locked);
    auto silent = makeSilent(60);
    m.update(silent, 0.0);
    CHECK(m.state() == PositionTrackingState::Weak);
    m.update(silent, 0.0);
    m.update(silent, 0.0);
    CHECK(m.state() == PositionTrackingState::Reacquiring);
}

// (c) Silence -> never locks.
void test_matcher_silence_no_lock() {
    auto r = makeUniqueRef();
    PositionMatcher m(r);
    m.begin();
    auto silent = makeSilent(60);
    for (int i = 0; i < 10; ++i) m.update(silent, 0.0);
    CHECK(m.state() != PositionTrackingState::Locked);
}

// (d) Ambiguous (repeated) -> no false jump to the wrong repeat. The position
// must stay stable (consecutive updates agree within 1 beat) and must NOT land
// on the middle (B) section.
void test_matcher_ambiguous_no_jump() {
    auto r = makeRepeatRef();
    PositionMatcher m(r);
    m.begin();
    auto q = makeLiveFrom(r, 0, 60); // copy of the 1st A section
    double prev = -1e9;
    bool stable = true;
    for (int i = 0; i < 8; ++i) {
        auto o = m.update(q, prev > -1e8 ? prev : 0.0);
        if (prev > -1e8 && std::abs(o.quarterBeatPosition - prev) > 1.0) stable = false;
        prev = o.quarterBeatPosition;
    }
    // Stable: no jumping between the two A occurrences.
    CHECK(stable);
    // With current/end-position semantics, the first A ends near frame 59 and
    // the second A near frame 179.  A repeated match may choose either, but it
    // must not land in the middle of the B section (around frame 90).
    CHECK(std::abs(prev - r.frames()[90].quarterBeatPosition) > 3.0);
}

// (e1) Small error (<0.5 beat) applied directly.
void test_transport_small_direct() {
    LiveTransport t;
    t.configure(120.0, 10.0);
    PositionObservation o;
    o.quarterBeatPosition = 10.3;
    o.confidence = 0.9f;
    o.ambiguityMargin = 0.5f;
    o.valid = true;
    o.globalMatch = false;
    t.submitPositionObservation(o, 10.0);
    t.processFrames(480, 48000);
    const double pos = t.snapshot().quarterBeatPosition;
    CHECK(pos > 10.2);
    CHECK(pos < 10.6);
}

// (e2) Medium error (0.5-4 beats) slewed (rate-limited, not a snap).
void test_transport_medium_slew() {
    LiveTransport t;
    t.configure(120.0, 10.0);
    PositionObservation o;
    o.quarterBeatPosition = 13.0; // error ~3 beats
    o.confidence = 0.9f;
    o.ambiguityMargin = 0.5f;
    o.valid = true;
    o.globalMatch = false;
    t.submitPositionObservation(o, 10.0);
    t.processFrames(480, 48000); // 10 ms
    const double pos = t.snapshot().quarterBeatPosition;
    // Slew is rate-limited: only a small step in 10 ms, not the full 3 beats.
    CHECK(pos > 10.0);
    CHECK(pos < 10.2);
}

// (e2b) The slew target PERSISTS and is applied on every callback, so a medium
// error converges toward the target over ~1.5 s (the gate-4 fix: the old design
// applied the 10 ms step once per ~2 s observation -> 200x too slow, never
// converged). After 150 callbacks the cursor must be near the target, not stuck.
void test_transport_slew_converges() {
    LiveTransport t;
    t.configure(120.0, 10.0);
    PositionObservation o;
    o.quarterBeatPosition = 13.0; // error ~3 beats
    o.confidence = 0.9f;
    o.ambiguityMargin = 0.5f;
    o.valid = true;
    o.globalMatch = false;
    t.submitPositionObservation(o, 10.0);
    for (int i = 0; i < 150; ++i) t.processFrames(480, 48000); // ~1.5 s
    const double pos = t.snapshot().quarterBeatPosition;
    CHECK(pos > 12.0); // converged toward the target (13.0)
    CHECK(pos < 14.0);
}

// (e3) Large error (>4 beats) NOT applied when ambiguous.
void test_transport_large_ambiguous_no_jump() {
    LiveTransport t;
    t.configure(120.0, 10.0);
    PositionObservation o;
    o.quarterBeatPosition = 20.0; // error ~10 beats
    o.confidence = 0.9f;
    o.ambiguityMargin = 0.3f; // ambiguous (>= 0.05)
    o.valid = true;
    o.globalMatch = false;
    t.submitPositionObservation(o, 10.0);
    t.processFrames(480, 48000);
    const double pos = t.snapshot().quarterBeatPosition;
    CHECK(pos < 12.0); // did not jump to 20
}

// (e4) Large error (>4 beats) applied when strong + low-ambiguity.
void test_transport_large_strong_applied() {
    LiveTransport t;
    t.configure(120.0, 10.0);
    PositionObservation o;
    o.quarterBeatPosition = 20.0; // error ~10 beats
    o.confidence = 0.95f; // strong (>= 0.88)
    o.ambiguityMargin = 0.02f; // low ambiguity (< 0.05)
    o.valid = true;
    o.globalMatch = false;
    t.submitPositionObservation(o, 10.0);
    t.processFrames(480, 48000);
    const double pos = t.snapshot().quarterBeatPosition;
    CHECK(pos > 18.0); // jumped to ~20
}

// (e5) Large error applied on the initial global lock (globalMatch).
void test_transport_global_lock_applied() {
    LiveTransport t;
    t.configure(120.0, 10.0);
    PositionObservation o;
    o.quarterBeatPosition = 20.0;
    o.confidence = 0.9f;
    o.ambiguityMargin = 0.3f; // ambiguous, but...
    o.valid = true;
    o.globalMatch = true; // ...it is the initial global lock
    t.submitPositionObservation(o, 10.0);
    t.processFrames(480, 48000);
    const double pos = t.snapshot().quarterBeatPosition;
    CHECK(pos > 18.0);
}

static void test_transport_tempo_target_and_fallback() {
    LiveTransport t; t.configure(120.0, 0.0);
    BeatObservation o; o.detectedBpm = 90.0; o.confidence = 0.95f; o.rms = 0.2f;
    o.tempoValid = true; o.phaseValid = true; o.phaseCorrectionBeats = 9.0;
    const double before = t.snapshot().quarterBeatPosition;
    t.submitTempoObservation(o);
    t.processFrames(480, 48000);
    const double after = t.snapshot().quarterBeatPosition;
    CHECK(after - before < 0.1); // phase correction must be ignored
    for (int i = 0; i < 250; ++i) t.processFrames(480, 48000);
    CHECK(t.snapshot().transportBpm > 90.0 && t.snapshot().transportBpm < 95.0);
    o.tempoValid = false; o.detectedBpm = 0.0; o.confidence = 0.0f;
    t.submitTempoObservation(o);
    for (int i = 0; i < 1000; ++i) t.processFrames(480, 48000);
    CHECK(t.snapshot().detectedBpm == 0.0);
    CHECK(std::isfinite(t.snapshot().transportBpm));
    CHECK(t.snapshot().transportBpm >= 115.0 && t.snapshot().transportBpm <= 121.0);
}

REGISTER_TEST(test_transport_tempo_target_and_fallback);
REGISTER_TEST(test_matcher_acquire_lock);
REGISTER_TEST(test_matcher_degrade_reacquire);
REGISTER_TEST(test_matcher_silence_no_lock);
REGISTER_TEST(test_matcher_ambiguous_no_jump);
REGISTER_TEST(test_transport_small_direct);
REGISTER_TEST(test_transport_medium_slew);
REGISTER_TEST(test_transport_slew_converges);
REGISTER_TEST(test_transport_large_ambiguous_no_jump);
REGISTER_TEST(test_transport_large_strong_applied);
REGISTER_TEST(test_transport_global_lock_applied);
