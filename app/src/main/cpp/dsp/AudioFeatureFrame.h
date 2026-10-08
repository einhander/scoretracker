#pragma once
#include <array>
#include <cstdint>
namespace temposcore {
struct AudioFeatureFrame {
    std::array<float, 12> chroma{};
    float onset = 0.0f;
    float energy = 0.0f;
    bool valid = false;
    // Absolute source-capture center within streamEpoch, not analyzer-local STFT time.
    int64_t centerAudioFrame = 0;
    uint64_t streamEpoch = 0;
    uint64_t continuityEpoch = 0;
    uint64_t positionGeneration = 0;
};
}
