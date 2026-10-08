#pragma once

#include <cstdint>

namespace temposcore {

// Source-capture time for one contiguous PCM batch. Position generation changes
// independently from streamEpoch; seeks never restart captured frame numbering.
struct AudioSourceSpan {
    uint64_t streamEpoch = 0;
    uint64_t continuityEpoch = 0;
    uint64_t positionGeneration = 0;
    uint64_t firstFrame = 0;
    uint32_t frameCount = 0;
};

} // namespace temposcore
