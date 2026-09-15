#pragma once
#include <array>
#include <vector>
namespace temposcore { class SpectralFlux final { public: explicit SpectralFlux(int rate, size_t bins=2049):rate_(rate),previous_(bins,0.0f){} std::array<float,3> process(const std::vector<float>& mag) noexcept; private:int rate_; std::vector<float> previous_; }; }
