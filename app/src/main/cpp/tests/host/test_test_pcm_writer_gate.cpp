#include "audio/TestPcmWriterGate.h"
#include "audio/SpscAudioRing.h"
#include "tests/host/test_main.h"

#include <atomic>
#include <mutex>
#include <thread>

using temposcore::TestPcmWriterGate;
using temposcore::SpscAudioRing;
using temposcore::AudioSourceSpan;
using temposcore::TestPcmSessionIdentity;
using temposcore::revokeSessionIfCurrent;

void test_test_pcm_writer_captured_before_pause_cannot_enter_restarted_session() {
    TestPcmWriterGate gate;
    const uint64_t firstSession = gate.openNextSession();
    CHECK(firstSession != 0);
    CHECK(!gate.tryEnter(firstSession)); // Reserved token cannot push before native startTest succeeds.
    CHECK(gate.activateSession(firstSession));

    // Mirrors pushTestAudio: session identity is captured and initial running
    // check passed, then writer pauses immediately before production tryEnter.
    const uint64_t pausedWriterToken = gate.sessionToken();
    CHECK(pausedWriterToken == firstSession);
    gate.closeAndWait(); // No writer registered, so control drain completes.
    const uint64_t secondSession = gate.openNextSession();
    CHECK(secondSession != 0 && secondSession != firstSession);
    CHECK(!gate.tryEnter(pausedWriterToken)); // Old token cannot become new producer.
    CHECK(gate.activeWriters() == 0);

    CHECK(gate.activateSession(secondSession));
    SpscAudioRing pcmRing(8);
    const float pcm[4] = {0, 1, 2, 3};
    uint64_t capturedSourceFrames = 0;
    const auto pushForSession = [&](uint64_t token) {
        return gate.runIfAdmitted(token, [&]() noexcept {
            const AudioSourceSpan span{31, 1, 5, capturedSourceFrames, 4};
            capturedSourceFrames += 4;
            pcmRing.writeStamped(pcm, 4, span);
        });
    };
    CHECK(!pushForSession(pausedWriterToken));
    CHECK(capturedSourceFrames == 0);
    CHECK(pcmRing.available() == 0);
    CHECK(pushForSession(secondSession));
    CHECK(capturedSourceFrames == 4);
    CHECK(pcmRing.available() == 4);
    gate.closeAndWait();
    CHECK(gate.activeWriters() == 0);
}

void test_test_pcm_writer_stop_closes_admission_then_drains_registered_writer() {
    TestPcmWriterGate gate;
    const uint64_t session = gate.openNextSession();
    CHECK(gate.activateSession(session));
    CHECK(gate.tryEnter(session));
    CHECK(!gate.tryEnter(session)); // SPSC admits only one test writer at a time.
    std::atomic<bool> stopReturned{false};
    std::thread stopper([&] {
        gate.closeAndWait();
        stopReturned.store(true, std::memory_order_release);
    });
    for (int i = 0; i < 100000 && !gate.isClosed(); ++i) std::this_thread::yield();
    CHECK(gate.isClosed());
    CHECK(!gate.tryEnter(session));
    CHECK(!stopReturned.load(std::memory_order_acquire));
    gate.leave(session);
    stopper.join();
    CHECK(stopReturned.load(std::memory_order_acquire));
    CHECK(gate.activeWriters() == 0);
}

void test_kotlinCallerSessionIdentityRejectsDelayedStartAndPushAcrossRestart() {
    TestPcmSessionIdentity identity;
    TestPcmWriterGate nativeGate;
    SpscAudioRing ring(8);
    const uint64_t oldPlaybackToken = 41;
    CHECK(identity.begin(oldPlaybackToken));
    const uint64_t oldNativeGate = nativeGate.openNextSession();
    CHECK(nativeGate.activateSession(oldNativeGate));
    // Playback thread is paused after owning its immutable token. UI stop
    // revokes it, then a new Kotlin playback run receives a greater token.
    identity.revoke(oldPlaybackToken);
    CHECK(nativeGate.closeSession(oldNativeGate));
    const uint64_t newPlaybackToken = 42;
    CHECK(identity.begin(newPlaybackToken));
    CHECK(!identity.begin(oldPlaybackToken)); // delayed old startTest cannot become current.
    CHECK(!identity.current(oldPlaybackToken));
    CHECK(identity.current(newPlaybackToken));
    const uint64_t newNativeGate = nativeGate.openNextSession();
    CHECK(nativeGate.activateSession(newNativeGate));

    // Revoke call arriving late for the old run cannot invalidate newer session.
    identity.revoke(oldPlaybackToken);
    CHECK(identity.current(newPlaybackToken));
    const float pcm[] = {1, 2, 3, 4};
    uint64_t capturedFrames = 0;
    const auto feed = [&](uint64_t callerToken, uint64_t nativeToken) {
        return identity.runIfCurrent(callerToken, nativeGate, nativeToken, [&]() noexcept {
            const AudioSourceSpan span{1, 1, 1, capturedFrames, 4};
            capturedFrames += 4;
            ring.writeStamped(pcm, 4, span);
        });
    };
    CHECK(!feed(oldPlaybackToken, newNativeGate)); // stale JNI token advances neither clock nor PCM ring.
    CHECK(capturedFrames == 0 && ring.available() == 0);
    CHECK(feed(newPlaybackToken, newNativeGate));
    CHECK(capturedFrames == 4 && ring.available() == 4);
}

void test_stale_revocation_cannot_close_or_idle_new_session() {
    std::mutex lifecycleMutex;
    std::atomic<uint64_t> activeCallerSession{41};
    TestPcmWriterGate gate;
    const uint64_t sessionA = gate.openNextSession();
    CHECK(gate.activateSession(sessionA));
    std::atomic<bool> running{true};
    std::atomic<bool> aPaused{false};
    std::atomic<bool> resumeA{false};
    std::atomic<bool> bStarted{false};

    std::thread revokeA([&] {
        revokeSessionIfCurrent(lifecycleMutex, activeCallerSession, 41, [&]() noexcept {
            // Deterministic pause at revocation critical step. B must serialize
            // behind this production helper, not interleave session effects.
            aPaused.store(true, std::memory_order_release);
            while (!resumeA.load(std::memory_order_acquire)) std::this_thread::yield();
            gate.closeSession(sessionA);
            running.store(false, std::memory_order_release);
        });
    });
    while (!aPaused.load(std::memory_order_acquire)) std::this_thread::yield();

    std::thread startB([&] {
        std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex);
        const uint64_t sessionB = gate.openNextSession();
        CHECK(sessionB != 0);
        CHECK(gate.activateSession(sessionB));
        activeCallerSession.store(42, std::memory_order_release);
        running.store(true, std::memory_order_release);
        bStarted.store(true, std::memory_order_release);
    });
    // B cannot publish until A's serialized invalidation completes.
    CHECK(!bStarted.load(std::memory_order_acquire));
    resumeA.store(true, std::memory_order_release);
    revokeA.join();
    startB.join();

    CHECK(bStarted.load(std::memory_order_acquire));
    CHECK(activeCallerSession.load(std::memory_order_acquire) == 42);
    CHECK(running.load(std::memory_order_acquire));
    const uint64_t sessionB = gate.sessionToken();
    CHECK(sessionB != 0 && gate.isActiveSession(sessionB));
    SpscAudioRing ring(8);
    const float pcm[] = {1, 2, 3, 4};
    const AudioSourceSpan span{1, 1, 1, 0, 4};
    CHECK(gate.runIfAdmitted(sessionB, [&]() noexcept { ring.writeStamped(pcm, 4, span); }));
    CHECK(ring.available() == 4);
}

REGISTER_TEST(test_test_pcm_writer_captured_before_pause_cannot_enter_restarted_session);
REGISTER_TEST(test_test_pcm_writer_stop_closes_admission_then_drains_registered_writer);
REGISTER_TEST(test_kotlinCallerSessionIdentityRejectsDelayedStartAndPushAcrossRestart);
REGISTER_TEST(test_stale_revocation_cannot_close_or_idle_new_session);
