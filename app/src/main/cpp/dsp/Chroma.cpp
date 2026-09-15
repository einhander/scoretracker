#include "dsp/Chroma.h"
#include <algorithm>
#include <cmath>
namespace temposcore { void ChromaExtractor::extract(const std::vector<float>& mag,std::array<float,12>& out) const noexcept { out.fill(0); float peak=0;for(float x:mag)peak=std::max(peak,x); for(size_t k=1;k<mag.size();++k){float f=static_cast<float>(k)*rate_/(2.0f*(mag.size()-1)); if(f<55||f>5000||mag[k]<peak*0.001f)continue; int p=static_cast<int>(std::lround(69+12*std::log2(f/440))); out[p%12]+=std::log1p(mag[k]); } float norm=0;for(float x:out)norm+=x*x;norm=std::sqrt(norm);if(norm>0)for(float&x:out)x/=norm; } }
