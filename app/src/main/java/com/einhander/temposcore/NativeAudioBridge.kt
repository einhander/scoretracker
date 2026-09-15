package com.einhander.temposcore

import com.einhander.temposcore.transport.PositionTrackingState
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
    external fun setScoreReference(ppq: Int, totalTicks: Long, noteChannels: IntArray, notePitches: IntArray, noteVelocities: IntArray, noteStarts: LongArray, noteEnds: LongArray, tempoTicks: LongArray, tempoValues: IntArray)
    external fun requestGlobalReacquire()
    private external fun getStateRaw(): DoubleArray

    fun state(): TransportSnapshot {
        val s = getStateRaw()
        return TransportSnapshot(
            transportBpm = s.getOrElse(0) { 0.0 },
            detectedBpm = s.getOrElse(1) { 0.0 },
            quarterBeatPosition = s.getOrElse(2) { 0.0 },
            beatConfidence = s.getOrElse(3) { 0.0 },
            rms = s.getOrElse(4) { 0.0 },
            running = s.getOrElse(5) { 0.0 } > 0.5,
            positionConfidence = s.getOrElse(6) { 0.0 },
            matchedQuarterBeatPosition = s.getOrElse(7) { 0.0 },
            positionErrorBeats = s.getOrElse(8) { 0.0 },
            positionState = PositionTrackingState.fromCode(s.getOrElse(9) { 0.0 }.toInt()),
            ambiguityMargin = s.getOrElse(10) { 0.0 },
            validContextSeconds = s.getOrElse(11) { 0.0 },
        )
    }
}
