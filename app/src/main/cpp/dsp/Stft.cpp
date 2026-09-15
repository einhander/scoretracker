#include "dsp/Stft.h"
#include <cmath>
namespace temposcore {
Stft::Stft(int rate,size_t n,size_t hop):sampleRate_(rate),fftSize_(n),hop_(hop),fft_(n),samples_(n),window_(n),magnitude_(n/2+1),spectrum_(n) {
    constexpr float pi=3.14159265358979323846f;
    for(size_t i=0;i<n;++i) window_[i]=0.5f*(1.0f-std::cos(2*pi*i/n)); // periodic Hann
}
bool Stft::process(const float* input,size_t count) noexcept {
    bool made=false;
    for(size_t i=0;i<count;++i) { samples_[fill_++]=input[i]; if(fill_==fftSize_) {
        frameEnergy_=0.0f;
        for(size_t k=0;k<fftSize_;++k) { const float value=samples_[k]*window_[k]; frameEnergy_+=value*value; spectrum_[k]={value,0}; }
        frameEnergy_=std::sqrt(frameEnergy_/fftSize_); fft_.forward(spectrum_.data());
        for(size_t k=0;k<magnitude_.size();++k) magnitude_[k]=std::abs(spectrum_[k]);
        ++frameIndex_; made=true; if(hop_<fftSize_) { for(size_t k=0;k<fftSize_-hop_;++k)samples_[k]=samples_[k+hop_]; fill_=fftSize_-hop_; } else fill_=0;
    }} return made;
}
}
