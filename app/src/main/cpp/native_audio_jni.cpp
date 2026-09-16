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


extern "C" JNIEXPORT jboolean JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_startTest(
        JNIEnv*, jobject, jint sampleRate) {
    return gEngine.startTest(sampleRate) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_pushTestAudio(
        JNIEnv* env, jobject, jfloatArray samples) {
    if (samples == nullptr) return;
    const jsize n = env->GetArrayLength(samples);
    if (n <= 0) return;
    jboolean isCopy = JNI_FALSE;
    jfloat* data = env->GetFloatArrayElements(samples, &isCopy);
    if (data == nullptr) return;
    gEngine.pushTestAudio(data, static_cast<size_t>(n));
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_stopTest(JNIEnv*, jobject) {
    gEngine.stop();
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

extern "C" JNIEXPORT void JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_requestGlobalReacquire(JNIEnv*, jobject) {
    gEngine.requestGlobalReacquire();
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_einhander_temposcore_NativeAudioBridge_getStateRaw(JNIEnv* env, jobject) {
    const auto state = gEngine.state();
    // Spec §28 semantic order (12 doubles).
    const jdouble values[12] = {
        state.transportBpm,               // 0
        state.detectedBpm,                // 1
        state.quarterBeatPosition,        // 2
        state.confidence,                 // 3 beatConfidence
        state.rms,                        // 4
        state.running ? 1.0 : 0.0,        // 5
        state.positionConfidence,         // 6
        state.matchedQuarterBeatPosition, // 7
        state.positionErrorBeats,         // 8
        static_cast<jdouble>(state.positionStateCode), // 9
        state.ambiguityMargin,            // 10
        state.validContextSeconds,        // 11
    };
    jdoubleArray result = env->NewDoubleArray(12);
    if (result != nullptr) env->SetDoubleArrayRegion(result, 0, 12, values);
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
