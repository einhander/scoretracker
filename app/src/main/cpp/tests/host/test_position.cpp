#include "position/DtwMatcher.h"
#include "position/ScoreReference.h"
#include "tests/host/test_main.h"
#include <cmath>
using namespace temposcore;
static ScoreReference makeRef(){MidiData m;m.ppq=10;m.totalTicks=1000;m.notes={{0,60,100,0,100},{0,64,100,120,220},{0,67,100,240,340},{0,62,100,360,460},{0,65,100,480,580},{0,69,100,600,700},{0,60,100,720,820},{0,67,100,840,1000}};ScoreReference r;CHECK(buildScoreReference(m,r));return r;}
static FeatureRing makeLive(const ScoreReference&r,size_t start=0){FeatureRing q;for(size_t i=start;i<start+10&&i<r.frames().size();++i){AudioFeatureFrame f;f.valid=true;f.chroma=r.frames()[i].chroma;f.onset=r.frames()[i].onset;q.push(f);}return q;}
static FeatureRing resample(const ScoreReference&r,float ratio){FeatureRing q;for(size_t i=0;i<10;++i){size_t source=static_cast<size_t>(std::lround(i/ratio));if(source>=r.frames().size())source=r.frames().size()-1;AudioFeatureFrame f;f.valid=true;f.chroma=r.frames()[source].chroma;f.onset=r.frames()[source].onset;q.push(f);}return q;}
void test_position_exact(){auto r=makeRef();auto o=DtwMatcher(r).global(makeLive(r));CHECK(o.valid||o.matchQuality>0);CHECK_NEAR(o.quarterBeatPosition,r.frames()[0].quarterBeatPosition,.5);}
void test_position_stretch(){auto r=makeRef();for(float ratio:{.8f,1.2f}){auto o=DtwMatcher(r).global(resample(r,ratio));CHECK_NEAR(o.quarterBeatPosition,r.frames()[0].quarterBeatPosition,.5);CHECK(o.matchQuality>.5f);}}
void test_position_wrong(){auto r=makeRef();auto good=DtwMatcher(r).global(makeLive(r));FeatureRing q;for(int i=0;i<10;++i){AudioFeatureFrame f;f.valid=true;f.chroma[11]=1;q.push(f);}CHECK(good.matchQuality>DtwMatcher(r).global(q).matchQuality+.001f);}
void test_position_repeat(){auto r=makeRef();auto o=DtwMatcher(r).global(makeLive(r));CHECK(o.ambiguityMargin<.5f);}
void test_position_continuity(){auto r=makeRef();auto o=DtwMatcher(r).local(makeLive(r,1),r.frames()[1].nominalSeconds);CHECK(o.quarterBeatPosition>=r.frames()[0].quarterBeatPosition);}
void test_position_silence(){auto r=makeRef();FeatureRing q;for(int i=0;i<10;++i)q.push(AudioFeatureFrame{});CHECK(DtwMatcher(r).global(q).confidence<.5f);}
REGISTER_TEST(test_position_exact);REGISTER_TEST(test_position_stretch);REGISTER_TEST(test_position_wrong);REGISTER_TEST(test_position_repeat);REGISTER_TEST(test_position_continuity);REGISTER_TEST(test_position_silence);
