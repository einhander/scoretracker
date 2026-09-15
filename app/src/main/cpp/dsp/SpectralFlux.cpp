#include "dsp/SpectralFlux.h"
#include <algorithm>
namespace temposcore { std::array<float,3> SpectralFlux::process(const std::vector<float>& mag) noexcept { std::array<float,3> out{}; const size_t count=std::min(mag.size(),previous_.size()); for(size_t k=1;k<count;++k){float f=static_cast<float>(k)*rate_/(2.0f*(mag.size()-1));int b=(f<40)?-1:(f<220)?0:(f<2000)?1:(f<10000)?2:-1;if(b>=0)out[b]+=std::max(0.0f,mag[k]-previous_[k]);} for(size_t k=0;k<count;++k)previous_[k]=mag[k];return out; } }
