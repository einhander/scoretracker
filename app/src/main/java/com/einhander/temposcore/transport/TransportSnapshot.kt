package com.einhander.temposcore.transport

/** Score-following tracking state (mirrors the native PositionTrackingState codes). */
enum class PositionTrackingState(val code: Int) {
    Idle(0),
    Acquiring(1),
    Locked(2),
    Weak(3),
    Reacquiring(4);

    companion object {
        fun fromCode(code: Int): PositionTrackingState =
            entries.firstOrNull { it.code == code } ?: Idle
    }
}

data class TransportSnapshot(
    // Beat/transport loop (fast).
    val transportBpm: Double,
    val detectedBpm: Double,
    val quarterBeatPosition: Double,
    val beatConfidence: Double,
    val rms: Double,
    val running: Boolean,
    // Score-following loop (slow) — spec §28 fields 6-11.
    val positionConfidence: Double,
    val matchedQuarterBeatPosition: Double,
    val positionErrorBeats: Double,
    val positionState: PositionTrackingState,
    val ambiguityMargin: Double,
    val validContextSeconds: Double,
)
