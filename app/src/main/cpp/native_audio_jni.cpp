#include "audio/OboeInputEngine.h"

#include <jni.h>

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
