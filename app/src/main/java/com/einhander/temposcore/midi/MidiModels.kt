package com.einhander.temposcore.midi

data class MidiNote(
    val track: Int,
    val channel: Int,
    val pitch: Int,
    val velocity: Int,
    val startTick: Long,
    val endTick: Long,
) {
    val durationTicks: Long get() = (endTick - startTick).coerceAtLeast(0)
}

data class TempoEvent(
    val tick: Long,
    val microsecondsPerQuarter: Int,
) {
    val bpm: Double get() = 60_000_000.0 / microsecondsPerQuarter.toDouble()
}

data class TimeSignatureEvent(
    val tick: Long,
    val numerator: Int,
    val denominator: Int,
)

data class MidiTrackInfo(
    val index: Int, val name: String?, val noteCount: Int,
    val pitchMin: Int?, val pitchMax: Int?, val channels: List<Int>,
)

data class MidiScore(
    val format: Int,
    val ppq: Int,
    val notes: List<MidiNote>,
    val tempoMap: List<TempoEvent>,
    val timeSignatures: List<TimeSignatureEvent>,
    val totalTicks: Long,
    val tracks: List<MidiTrackInfo>,
) {
    val initialBpm: Double get() = tempoMap.firstOrNull()?.bpm ?: 120.0
    fun tickToQuarterBeats(tick: Long): Double = tick.toDouble() / ppq.toDouble()
    fun noteStartBeat(note: MidiNote): Double = tickToQuarterBeats(note.startTick)
    fun noteEndBeat(note: MidiNote): Double = tickToQuarterBeats(note.endTick)
}
