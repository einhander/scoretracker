#include "dsp/SpectralFlux.h"
#include "tests/host/test_main.h"
#include <cmath>
#include <vector>
void test_flux(){temposcore::SpectralFlux f(48000,2049);std::vector<float>a(2049),b(2049);auto z=f.process(a);CHECK_NEAR(z[0]+z[1]+z[2],0,1e-6f);z=f.process(a);CHECK_NEAR(z[0]+z[1]+z[2],0,1e-6f);b[5]=1;z=f.process(b);CHECK(z[0]>0);CHECK_NEAR(z[1]+z[2],0,1e-6f);}
REGISTER_TEST(test_flux);
