#pragma once
#include <array>
#include <vector>
namespace temposcore { class ChromaExtractor final { public: explicit ChromaExtractor(int rate):rate_(rate){} void extract(const std::vector<float>& mag,std::array<float,12>& out) const noexcept; private:int rate_; }; using Chroma=ChromaExtractor; }
