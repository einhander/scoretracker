package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore

/** Only simultaneous notes may share accidental columns. Pixel proximity is not ownership. */
fun sharesAccidentalColumns(startTick: Long, otherStartTick: Long): Boolean =
    startTick == otherStartTick

/** Horizontal-only magnification; the transport beat remains anchored at the cursor. */
fun horizontalZoom(startZoom: Float, startSpan: Float, span: Float): Float {
    if (!startSpan.isFinite() || !span.isFinite() || startSpan <= 0f || span <= 0f) return startZoom
    return (startZoom * span / startSpan).coerceIn(0.1f, 4f)
}

/** Full-selection extents in line-gap units, independent of the visible beat window. */
data class StaffExtent(val top: Float = 2f, val bottom: Float = 2f) {
    fun including(step: Int, accidental: Boolean, laneOffset: Float = 0f,
        aboveHead: Float = 2.25f, belowHead: Float = if (accidental) 0.95f else 0.55f): StaffExtent = StaffExtent(
        maxOf(top, step / 2f - laneOffset + aboveHead),
        maxOf(bottom, -step / 2f + laneOffset + belowHead),
    )
}

/** Rendering sizes only; onset/end beats and horizontal viewport scale remain unchanged. */
data class StaffNoteGeometry(val scale: Float) {
    val headXUnits: Float get() = 0.52f * scale
    val headYUnits: Float get() = 0.33f * scale
    val stemWidthUnits: Float get() = 0.055f * scale
    fun ribbonHalfThicknessUnits(lane: Int): Float = (if (lane == 0) 0.13f else 0.065f) * scale
    // Ribbon stems retain their 1.7-gap height; larger heads/outlines and signs need bottom space.
    val ribbonAboveHeadUnits: Float get() = 2.25f
    fun ribbonBelowHeadUnits(accidental: Boolean): Float =
        if (accidental) 0.95f * scale else maxOf(0.55f, headYUnits + 0.28f)
}

private val symbolNoteGeometry = StaffNoteGeometry(1f)
private val ribbonNoteGeometry = StaffNoteGeometry(1.3f)

fun staffNoteGeometry(display: DurationDisplay): StaffNoteGeometry =
    if (display == DurationDisplay.Ribbons) ribbonNoteGeometry else symbolNoteGeometry

/** Font-sized rows reserve space before drawing; dense/short views use the existing overflow path. */
fun staffLabelRows(height: Float, rowHeight: Float, density: Float): Int = when {
    height >= 220f * density && rowHeight * 3f < height * 0.3f -> 3
    rowHeight * 2f <= height * 0.4f -> 2
    else -> 1
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
