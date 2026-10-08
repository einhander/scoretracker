package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore

data class BeatViewport(
    val quarterBeatPosition: Double,
    val cursorX: Float,
    val pixelsPerQuarterBeat: Float,
    val leftX: Float,
    val rightX: Float,
) {
    fun beatToX(quarterBeat: Double): Float =
        cursorX + ((quarterBeat - quarterBeatPosition) * pixelsPerQuarterBeat).toFloat()

    fun xToBeat(x: Float): Double =
        quarterBeatPosition + (x - cursorX) / pixelsPerQuarterBeat

    fun visibleBeatRange(beatMargin: Double = 0.0): ClosedFloatingPointRange<Double> =
        (xToBeat(leftX) - beatMargin)..(xToBeat(rightX) + beatMargin)
}

enum class NoteTimeState { Future, Active, Past }

data class NoteInterval(val startBeat: Double, val endBeat: Double) {
    fun stateAt(position: Double): NoteTimeState = when {
        position < startBeat -> NoteTimeState.Future
        startBeat < endBeat && position < endBeat -> NoteTimeState.Active
        else -> NoteTimeState.Past
    }

    fun intersects(fromBeat: Double, toBeat: Double): Boolean =
        endBeat > startBeat && fromBeat <= toBeat && startBeat <= toBeat && endBeat >= fromBeat

    fun visibleSegment(fromBeat: Double, toBeat: Double): NoteInterval? {
        if (endBeat <= startBeat || !intersects(fromBeat, toBeat)) return null
        return NoteInterval(maxOf(startBeat, fromBeat), minOf(endBeat, toBeat))
    }

    fun headVisible(fromBeat: Double, toBeat: Double): Boolean = startBeat in fromBeat..toBeat
}

fun noteInterval(score: MidiScore, note: MidiNote): NoteInterval =
    NoteInterval(score.noteStartBeat(note), score.noteEndBeat(note))

data class NoteStartGroup(
    val startTick: Long,
    val startBeat: Double,
    val notes: List<MidiNote>,
    val uniquePitches: List<Int>,
)

fun groupNotesByStartTick(score: MidiScore, notes: List<MidiNote>): List<NoteStartGroup> =
    notes.groupBy { it.startTick }.entries
        .sortedBy { it.key }
        .map { (tick, events) ->
            NoteStartGroup(tick, score.tickToQuarterBeats(tick), events, events.map { it.pitch }.distinct().sorted())
        }
