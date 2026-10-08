#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <thread>
#include <utility>

namespace temposcore {

// Epoch-tagged admission and drain gate for non-RT test-PCM writers. The gate
// combines session token, closed bit and in-flight count in one lock-free word:
// a token captured before stop can never register in a later session.
class TestPcmWriterGate final {
public:
    static_assert(std::atomic<uint64_t>::is_always_lock_free,
                  "Test PCM session gate must be lock-free on supported ABIs");
    static constexpr uint32_t CountBits = 20;
    static constexpr uint64_t CountMask = (uint64_t{1} << CountBits) - 1;
    static constexpr uint64_t ClosedBit = uint64_t{1} << CountBits;
    static constexpr uint64_t ActiveBit = uint64_t{1} << (CountBits + 1);
    static constexpr uint32_t EpochShift = CountBits + 2;
    static constexpr uint64_t EpochMask = UINT64_MAX & ~(CountMask | ClosedBit | ActiveBit);

    uint64_t sessionToken() const noexcept {
        const uint64_t state = state_.load(std::memory_order_acquire);
        return (state & ClosedBit) ? 0 : state & EpochMask;
    }

    bool activateSession(uint64_t token) noexcept {
        if (!token) return false;
        uint64_t state = state_.load(std::memory_order_acquire);
        for (;;) {
            if ((state & EpochMask) != token || (state & ClosedBit)) return false;
            if (state & ActiveBit) return true;
            if (state_.compare_exchange_weak(state, state | ActiveBit, std::memory_order_acq_rel,
                                             std::memory_order_acquire)) return true;
        }
    }

    bool isActiveSession(uint64_t token) const noexcept {
        const uint64_t state = state_.load(std::memory_order_acquire);
        return token && (state & EpochMask) == token && (state & ActiveBit) && !(state & ClosedBit);
    }

    bool tryEnter(uint64_t capturedToken) noexcept {
        if (!capturedToken) return false;
        uint64_t state = state_.load(std::memory_order_acquire);
        for (;;) {
            // A single-producer ring may have at most one admitted test writer.
            if ((state & ClosedBit) || !(state & ActiveBit) || (state & EpochMask) != capturedToken ||
                (state & CountMask) != 0) return false;
            if (state_.compare_exchange_weak(state, state + 1, std::memory_order_acq_rel,
                                             std::memory_order_acquire)) return true;
        }
    }

    template <typename Action>
    bool runIfAdmitted(uint64_t capturedToken, Action&& action) noexcept {
        if (!tryEnter(capturedToken)) return false;
        action();
        leave(capturedToken);
        return true;
    }

    void leave(uint64_t /*capturedToken*/) noexcept {
        state_.fetch_sub(1, std::memory_order_release);
    }

    bool matchesSession(uint64_t token) const noexcept {
        if (!token) return false;
        return (state_.load(std::memory_order_acquire) & EpochMask) == token;
    }

    bool closeSession(uint64_t token) noexcept {
        if (!token) return false;
        uint64_t state = state_.load(std::memory_order_acquire);
        for (;;) {
            if ((state & EpochMask) != token) return false;
            if (state & ClosedBit) return true;
            if (state_.compare_exchange_weak(state, state | ClosedBit, std::memory_order_acq_rel,
                                             std::memory_order_acquire)) return true;
        }
    }

    bool closeSessionAndWait(uint64_t token) noexcept {
        if (!closeSession(token)) return false;
        while (activeWriters()) std::this_thread::yield();
        return true;
    }

    void closeAndWait() noexcept {
        state_.fetch_or(ClosedBit, std::memory_order_acq_rel);
        while (state_.load(std::memory_order_acquire) & CountMask) std::this_thread::yield();
    }

    uint64_t openNextSession() noexcept {
        uint64_t state = state_.load(std::memory_order_acquire);
        for (;;) {
            if (!(state & ClosedBit) || (state & CountMask)) return 0;
            const uint64_t currentEpoch = (state & EpochMask) >> EpochShift;
            const uint64_t epochMask = EpochMask >> EpochShift;
            if (currentEpoch == epochMask) return 0; // Exhaustion fails closed; never reuse token.
            const uint64_t next = (currentEpoch + 1) << EpochShift;
            if (state_.compare_exchange_weak(state, next, std::memory_order_acq_rel,
                                             std::memory_order_acquire)) return next;
        }
    }

    bool isClosed() const noexcept {
        return (state_.load(std::memory_order_acquire) & ClosedBit) != 0;
    }
    uint32_t activeWriters() const noexcept {
        return static_cast<uint32_t>(state_.load(std::memory_order_acquire) & CountMask);
    }

private:
    std::atomic<uint64_t> state_{ClosedBit};
};

// Identity bridge for caller-created Kotlin playback tokens. begin() is called
// under engine lifecycle mutex; revoke() may race from JNI control thread.
class TestPcmSessionIdentity final {
public:
    static_assert(std::atomic<uint64_t>::is_always_lock_free,
                  "Playback session identity must be lock-free on supported ABIs");

    bool begin(uint64_t callerToken) noexcept {
        if (!callerToken || callerToken <= latestStarted_ ||
            callerToken <= revokedThrough_.load(std::memory_order_acquire)) return false;
        latestStarted_ = callerToken;
        return callerToken > revokedThrough_.load(std::memory_order_acquire);
    }

    void revoke(uint64_t callerToken) noexcept {
        if (!callerToken) return;
        uint64_t revoked = revokedThrough_.load(std::memory_order_relaxed);
        while (revoked < callerToken &&
               !revokedThrough_.compare_exchange_weak(revoked, callerToken,
                   std::memory_order_release, std::memory_order_relaxed)) {}
    }

    bool current(uint64_t callerToken) const noexcept {
        return callerToken != 0 && callerToken > revokedThrough_.load(std::memory_order_acquire);
    }
    bool latestStarted(uint64_t callerToken) const noexcept { return latestStarted_ == callerToken; }
    template <typename Action>
    bool runIfCurrent(uint64_t callerToken, TestPcmWriterGate& writerGate,
                      uint64_t nativeGateToken, Action&& action) const noexcept {
        if (!current(callerToken)) return false;
        return writerGate.runIfAdmitted(nativeGateToken, std::forward<Action>(action));
    }

private:
    std::atomic<uint64_t> revokedThrough_{0};
    uint64_t latestStarted_ = 0;
};

// Production helper for session-owned shutdown side effects. Lock order:
// lifecycleMutex -> testPcmWriterMutex -> writer-gate admission/drain. Revocation
// only closes admission here; it never drains while holding lifecycleMutex,
// because an admitted producer may need testPcmWriterMutex before leaving.
template <typename Invalidate>
bool revokeSessionIfCurrent(std::mutex& lifecycleMutex,
                            std::atomic<uint64_t>& activeCallerSession,
                            uint64_t callerToken,
                            Invalidate&& invalidate) noexcept {
    std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex);
    if (!callerToken || activeCallerSession.load(std::memory_order_acquire) != callerToken) return false;
    invalidate();
    activeCallerSession.store(0, std::memory_order_release);
    return true;
}

} // namespace temposcore
