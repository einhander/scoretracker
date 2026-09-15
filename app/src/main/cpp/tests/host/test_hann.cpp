#include "dsp/Stft.h"
#include "tests/host/test_main.h"
#include <cmath>
void test_hann(){temposcore::Stft s(48000,64,16);const auto&w=s.window();for(size_t i:{size_t(0),size_t(16),size_t(32),size_t(48)})CHECK_NEAR(w[i],.5f*(1-std::cos(2*3.14159265358979323846f*i/64)),1e-6f);for(size_t i=1;i<32;++i)CHECK_NEAR(w[i],w[64-i],1e-6f);CHECK_NEAR(w[0],0,1e-6f);CHECK_NEAR(w[32],1,1e-6f);}
REGISTER_TEST(test_hann);
