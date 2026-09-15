#include "dsp/Fft.h"
#include <cmath>
namespace temposcore {
Fft::Fft(size_t size) : size_(size), bitReverse_(size), twiddles_(size / 2) {
    for (size_t i=0;i<size_;++i) { size_t x=i,r=0; for(size_t b=1;b<size_;b<<=1){r=(r<<1)|(x&1);x>>=1;} bitReverse_[i]=r; }
    constexpr float pi=3.14159265358979323846f;
    for(size_t k=0;k<size_/2;++k) twiddles_[k]=std::polar(1.0f,-2.0f*pi*static_cast<float>(k)/size_);
}
void Fft::forward(std::complex<float>* data) const noexcept {
    for(size_t i=0;i<size_;++i) if(i<bitReverse_[i]) std::swap(data[i],data[bitReverse_[i]]);
    for(size_t len=2;len<=size_;len<<=1) { const size_t half=len/2, step=size_/len;
        for(size_t base=0;base<size_;base+=len) for(size_t j=0;j<half;++j) { auto u=data[base+j],v=data[base+j+half]*twiddles_[j*step]; data[base+j]=u+v; data[base+j+half]=u-v; }
    }
}
}
