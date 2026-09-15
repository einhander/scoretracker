#include "dsp/Fft.h"
#include "tests/host/test_main.h"
#include <complex>
#include <cmath>
void test_fft() {
    constexpr size_t n=64; std::complex<float> x[n], expected[n];
    for(size_t i=0;i<n;++i)x[i]={std::sin(i*0.37f)+0.2f*std::cos(i*0.11f),std::cos(i*0.23f)};
    for(size_t k=0;k<n;++k){expected[k]={};for(size_t j=0;j<n;++j)expected[k]+=x[j]*std::polar(1.0f,-2.0f*3.14159265358979323846f*k*j/n);}
    temposcore::Fft f(n);f.forward(x);for(size_t k=0;k<n;++k)CHECK(std::abs(x[k]-expected[k])/std::max(1.0f,std::abs(expected[k]))<1e-3f);
    std::complex<float> delta[n]{};delta[0]=1;f.forward(delta);for(auto v:delta)CHECK_NEAR(std::abs(v),1,1e-5f);
    std::complex<float> sine[n]{};for(size_t i=0;i<n;++i)sine[i]={std::sin(2*3.14159265358979323846f*5*i/n),0};f.forward(sine);
    for(size_t k=0;k<n;++k)if(k!=5&&k!=n-5)CHECK(std::abs(sine[k])<1e-3f);
}
REGISTER_TEST(test_fft);
