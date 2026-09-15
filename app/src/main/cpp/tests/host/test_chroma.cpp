#include "dsp/Chroma.h"
#include "dsp/Stft.h"
#include "tests/host/test_main.h"
#include <cmath>
#include <algorithm>
#include <array>
static int tone(float hz,std::array<float,12>&out){temposcore::Stft s(48000);float x[4096];for(int i=0;i<4096;++i)x[i]=std::sin(2*3.14159265358979323846f*hz*i/48000);CHECK(s.process(x,4096));temposcore::ChromaExtractor(48000).extract(s.magnitude(),out);return int(std::max_element(out.begin(),out.end())-out.begin());}
void test_chroma(){std::array<float,12> c{};CHECK(tone(440,c)==9);CHECK(tone(10000,c)==0);for(float x:c)CHECK_NEAR(x,0,1e-3f);}
REGISTER_TEST(test_chroma);
