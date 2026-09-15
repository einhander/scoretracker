#include "audio/AudioAnalyzer.h"
#include <chrono>
#include <system_error>
namespace temposcore {
bool AudioAnalyzer::start(int32_t sampleRate) {
    if (running_.exchange(true, std::memory_order_acq_rel)) return false;
    framesConsumed_.store(0, std::memory_order_relaxed);
    worker_ = std::thread(&AudioAnalyzer::run, this, sampleRate);
    return true;
}
void AudioAnalyzer::stop() noexcept {
    if (!running_.exchange(false, std::memory_order_acq_rel)) return;
    if (worker_.joinable()) worker_.join();
}
void AudioAnalyzer::run(int32_t /*sampleRate*/) noexcept {
    float buffer[1024];
    while (running_.load(std::memory_order_acquire)) {
        const size_t count = ring_.read(buffer, 1024);
        if (count) framesConsumed_.fetch_add(count, std::memory_order_relaxed);
        else {
            try {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            } catch (const std::system_error&) {
                // Keep looping; stop signal remains authoritative.
            }
        }
    }
}
}
