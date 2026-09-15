#pragma once
#include "dsp/AudioFeatureFrame.h"
#include <array>
#include <cstddef>
namespace temposcore { class FeatureRing final { public: static constexpr size_t Capacity=200; bool push(const AudioFeatureFrame&f) noexcept{frames_[write_%Capacity]=f;++write_;if(size()<Capacity)++count_;return true;} size_t size()const noexcept{return count_;} void clear()noexcept{write_=0;count_=0;} size_t copy(std::array<AudioFeatureFrame,Capacity>&out)const noexcept{size_t n=count_;for(size_t i=0;i<n;++i)out[i]=frames_[(write_-n+i)%Capacity];return n;} private:std::array<AudioFeatureFrame,Capacity>frames_{};size_t write_=0,count_=0;}; }
