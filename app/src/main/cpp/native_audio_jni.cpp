#include "audio/OboeInputEngine.h"
#include "position/ScoreReference.h"

#include <jni.h>
#include <vector>

namespace {
temposcore::OboeInputEngine gEngine;
}

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_initialize(
        JNIEnv*, jobject, jdouble expectedBpm, jdouble startQuarterBeat) {
    gEngine.initialize(expectedBpm, startQuarterBeat);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_start(JNIEnv*, jobject) {
    return gEngine.start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_stop(JNIEnv*, jobject) {
    gEngine.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_setExpectedBpm(
        JNIEnv*, jobject, jdouble bpm) {
    gEngine.setExpectedBpm(bpm);
}

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_resetPosition(
        JNIEnv*, jobject, jdouble startQuarterBeat) {
    gEngine.resetPosition(startQuarterBeat);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_getStateRaw(JNIEnv* env, jobject) {
    const auto state = gEngine.state();
    const jdouble values[6] = {
        state.transportBpm,
        state.detectedBpm,
        state.quarterBeatPosition,
        state.confidence,
        state.rms,
        state.running ? 1.0 : 0.0,
    };
    jdoubleArray result = env->NewDoubleArray(6);
    if (result != nullptr) env->SetDoubleArrayRegion(result, 0, 6, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_setScoreReference(
        JNIEnv* env, jobject, jint ppq, jlong totalTicks, jintArray channels,
        jintArray pitches, jintArray velocities, jlongArray starts, jlongArray ends,
        jlongArray tempoTicks, jintArray tempoValues) {
    temposcore::MidiData data;
    data.ppq = ppq; data.totalTicks = totalTicks;
    const jsize n = env->GetArrayLength(pitches);
    std::vector<jint> ch(n), pi(n), ve(n); std::vector<jlong> st(n), en(n);
    env->GetIntArrayRegion(channels,0,n,ch.data()); env->GetIntArrayRegion(pitches,0,n,pi.data());
    env->GetIntArrayRegion(velocities,0,n,ve.data()); env->GetLongArrayRegion(starts,0,n,st.data()); env->GetLongArrayRegion(ends,0,n,en.data());
    data.notes.resize(n); for(jsize i=0;i<n;++i)data.notes[i]={ch[i],pi[i],ve[i],st[i],en[i]};
    const jsize tn=env->GetArrayLength(tempoTicks); std::vector<jlong> tt(tn); std::vector<jint> tv(tn);
    env->GetLongArrayRegion(tempoTicks,0,tn,tt.data()); env->GetIntArrayRegion(tempoValues,0,tn,tv.data());
    data.tempos.resize(tn);for(jsize i=0;i<tn;++i)data.tempos[i]={tt[i],tv[i]};
    gEngine.setScoreReference(data);
}
