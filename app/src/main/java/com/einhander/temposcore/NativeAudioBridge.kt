package com.einhander.temposcore

import com.einhander.temposcore.transport.TransportSnapshot

/** JNI boundary. Engine time lives in the native audio callback, not in Android timers. */
object NativeAudioBridge {
    init {
        System.loadLibrary("temposcore_native")
    }

    external fun initialize(expectedBpm: Double, startQuarterBeat: Double)
    external fun start(): Boolean
    external fun stop()
    external fun setExpectedBpm(bpm: Double)
    external fun resetPosition(startQuarterBeat: Double)
    private external fun getStateRaw(): DoubleArray

    fun state(): TransportSnapshot {
        val s = getStateRaw()
        return TransportSnapshot(
            transportBpm = s.getOrElse(0) { 0.0 },
            detectedBpm = s.getOrElse(1) { 0.0 },
            quarterBeatPosition = s.getOrElse(2) { 0.0 },
            confidence = s.getOrElse(3) { 0.0 },
            rms = s.getOrElse(4) { 0.0 },
            running = s.getOrElse(5) { 0.0 } > 0.5,
        )
    }
}
