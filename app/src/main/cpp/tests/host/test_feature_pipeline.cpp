#include "dsp/Stft.h"
#include "dsp/Chroma.h"
#include "dsp/SpectralFlux.h"
#include "tests/host/test_main.h"
#include <cmath>
#include <array>
void test_feature_pipeline(){
    temposcore::Stft s(48000); temposcore::ChromaExtractor c(48000); temposcore::SpectralFlux f(48000);
    int64_t next=2048,count=0,first=0; float silent=0,loud=0;
    for(int block=0;block<75;++block){ float x[1024];
        for(int i=0;i<1024;++i){int sample=block*1024+i;x[i]=(block<5?0.0f:0.8f*std::sin(2*3.14159265358979323846f*440*sample/48000));}
        if(s.process(x,1024)){ f.process(s.magnitude()); int64_t center=(s.frameIndex()-1)*s.hop()+s.fftSize()/2;
            if(center>=next){std::array<float,12>ch{};c.extract(s.magnitude(),ch);if(!count)first=center;if(block==0)silent=s.frameEnergy();else loud=s.frameEnergy();++count;next=center+4800;}
        }
    }
    CHECK(count>=15&&count<=16); CHECK(first>0&&std::abs(first-2048)<=1); CHECK(loud>silent*10); CHECK(silent<0.0001f&&loud>0.1f);
}
REGISTER_TEST(test_feature_pipeline);
