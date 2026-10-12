package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore

/** Horizontal-only magnification; the transport beat remains anchored at the cursor. */
fun horizontalZoom(startZoom: Float, startSpan: Float, span: Float): Float {
    if (!startSpan.isFinite() || !span.isFinite() || startSpan <= 0f || span <= 0f) return startZoom
    return (startZoom * span / startSpan).coerceIn(0.5f, 4f)
}

/** Full-selection extents in line-gap units, independent of the visible beat window. */
data class StaffExtent(val top: Float = 2f, val bottom: Float = 2f) {
    fun including(step: Int, accidental: Boolean, laneOffset: Float = 0f): StaffExtent = StaffExtent(
        maxOf(top, step / 2f - laneOffset + 2.25f),
        maxOf(bottom, -step / 2f + laneOffset + if (accidental) 0.95f else 0.55f),
    )
}

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
