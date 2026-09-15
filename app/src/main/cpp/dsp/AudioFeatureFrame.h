#pragma once
#include <array>
#include <cstdint>
namespace temposcore { struct AudioFeatureFrame {
    std::array<float,12> chroma{}; float onset=0.0f; float energy=0.0f; bool valid=false; int64_t centerAudioFrame=0;
}; }
