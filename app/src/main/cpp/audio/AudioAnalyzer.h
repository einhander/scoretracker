#pragma once
#include "audio/SpscAudioRing.h"
#include <atomic>
#include <cstdint>
#include <thread>
namespace temposcore {
class AudioAnalyzer final {
public:
    explicit AudioAnalyzer(SpscAudioRing& ring) noexcept : ring_(ring) {}
    ~AudioAnalyzer() { stop(); }
    bool start(int32_t sampleRate);
    void stop() noexcept; // Non-real-time only: join may block.
    uint64_t framesConsumed() const noexcept { return framesConsumed_.load(std::memory_order_relaxed); }
private:
    void run(int32_t sampleRate) noexcept;
    SpscAudioRing& ring_;
    std::thread worker_;
    std::atomic<bool> running_{false};
    std::atomic<uint64_t> framesConsumed_{0};
};
}
