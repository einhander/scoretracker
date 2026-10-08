package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffGeometryTest {
    private fun score(notes: List<MidiNote> = emptyList(), meters: List<TimeSignatureEvent> = emptyList()) = MidiScore(
        format = 1, ppq = 480, notes = notes, tempoMap = emptyList(), timeSignatures = meters,
        totalTicks = 10000, tracks = emptyList(),
    )

    @Test fun noteDurationAndIntervalClippingPreserveMidiTime() {
        val notes = listOf(
            MidiNote(0, 0, 60, 90, 0, 480), MidiNote(0, 0, 61, 90, 0, 960),
            MidiNote(0, 0, 62, 90, 240, 360), MidiNote(0, 0, 63, 90, 11, 148),
        )
        val s = score(notes)
        val durations = notes.map { noteInterval(s, it).endBeat - noteInterval(s, it).startBeat }
        assertEquals(listOf(1.0, 2.0, 0.25), durations.take(3))
        assertEquals(137.0 / 480, durations.last(), 1e-12)
        val viewport = BeatViewport(2.0, 100f, 50f, 0f, 200f)
        assertEquals(100f, viewport.beatToX(2.0), 1e-6f)
        assertEquals(50f, viewport.beatToX(1.0) - viewport.beatToX(0.0), 1e-6f)
        assertEquals(100f, viewport.beatToX(2.0) - viewport.beatToX(0.0), 1e-6f)

        val clippedLeft = NoteInterval(-2.0, 1.0)
        assertTrue(clippedLeft.intersects(0.0, 3.0))
        assertEquals(NoteInterval(0.0, 1.0), clippedLeft.visibleSegment(0.0, 3.0))
        assertFalse(clippedLeft.headVisible(0.0, 3.0))
        val clippedRight = NoteInterval(2.0, 8.0)
        assertEquals(NoteInterval(2.0, 4.0), clippedRight.visibleSegment(0.0, 4.0))
        assertEquals(8.0, clippedRight.endBeat, 0.0)
        assertNull(NoteInterval(2.0, 2.0).visibleSegment(0.0, 4.0))
        assertNull(NoteInterval(2.0, 1.0).visibleSegment(0.0, 4.0))
        assertTrue(NoteInterval(2.0, 2.0).headVisible(0.0, 4.0))
    }

    @Test fun viewportConversionIsInverseAcrossViewGeometry() {
        for ((width, position, cursor) in listOf(Triple(100f, -5.0, 21f), Triple(800f, 12.25, 300f), Triple(1920f, 0.0, 900f))) {
            val viewport = BeatViewport(position, cursor, width / 10f, 0f, width)
            for (x in listOf(0f, cursor, width / 2f, width)) {
                assertEquals(x, viewport.beatToX(viewport.xToBeat(x)), 1e-4f)
            }
        }
    }

    @Test fun noteStatesUseHalfOpenIntervalAndRetainOverlappingEvents() {
        val active = NoteInterval(2.0, 4.0)
        assertEquals(NoteTimeState.Future, active.stateAt(1.999))
        assertEquals(NoteTimeState.Active, active.stateAt(2.0))
        assertEquals(NoteTimeState.Active, active.stateAt(3.999))
        assertEquals(NoteTimeState.Past, active.stateAt(4.0))
        assertEquals(NoteTimeState.Past, NoteInterval(1.0, 1.0).stateAt(1.0))

        val notes = listOf(
            MidiNote(0, 0, 60, 90, 0, 480), MidiNote(0, 0, 60, 90, 240, 720),
            MidiNote(0, 1, 60, 90, 480, 481), MidiNote(0, 0, 64, 90, 0, 240),
        )
        val groups = groupNotesByStartTick(score(notes), notes)
        assertEquals(listOf(0L, 240L, 480L), groups.map { it.startTick })
        assertEquals(2, groups.first().notes.size)
        assertEquals(listOf(60, 64), groups.first().uniquePitches)
        assertEquals(1, groups.last().notes.single().durationTicks)
        assertEquals(listOf("C4", "E4"), groups.first().uniquePitches.map { ScoreNavigator.pitchName(it) })
        assertEquals(listOf("До4", "Ми4"), groups.first().uniquePitches.map { ScoreNavigator.pitchName(it, NoteNaming.Solfege) })
    }

    @Test fun meterBoundariesCoverDefaultsAndStandardMeters() {
        assertBoundaries(emptyList(), listOf(0.0, 4.0, 8.0, 12.0))
        assertBoundaries(listOf(TimeSignatureEvent(0, 4, 4)), listOf(0.0, 4.0, 8.0))
        assertBoundaries(listOf(TimeSignatureEvent(0, 3, 4)), listOf(0.0, 3.0, 6.0))
        assertBoundaries(listOf(TimeSignatureEvent(0, 6, 8)), listOf(0.0, 3.0, 6.0))
        assertBoundaries(listOf(TimeSignatureEvent(0, 5, 8)), listOf(0.0, 2.5, 5.0))
    }

    @Test fun midMeasureMeterChangeStartsBarAndAgreesWithBarBeat() {
        val s = score(meters = listOf(TimeSignatureEvent(0, 4, 4), TimeSignatureEvent(2400, 3, 4)))
        val boundaries = ScoreNavigator.measureBoundaries(s, 0.0, 9.0)
        assertEquals(listOf(0.0, 4.0, 5.0, 8.0), boundaries.map { it.quarterBeat })
        assertEquals(listOf(1, 2, 3, 4), boundaries.map { it.bar })
        assertEquals(2, ScoreNavigator.barBeatAt(s, 4.999).bar)
        assertEquals(3, ScoreNavigator.barBeatAt(s, 5.0).bar)
        assertEquals(3, ScoreNavigator.barBeatAt(s, 5.0).numerator)
        assertEquals(4, ScoreNavigator.barBeatAt(s, 8.0).bar)
        assertEquals(listOf(MeasureBoundary(8.0, 4, 3, 4)), ScoreNavigator.measureBoundaries(s, 7.0, 8.0))
    }

    @Test fun duplicateMeterTickLastEventWinsAndAlignedChangeDoesNotAddBar() {
        val s = score(meters = listOf(
            TimeSignatureEvent(0, 4, 4), TimeSignatureEvent(1920, 3, 4),
            TimeSignatureEvent(1920, 6, 8), TimeSignatureEvent(3360, 5, 8),
        ))
        val boundaries = ScoreNavigator.measureBoundaries(s, 0.0, 8.0)
        assertEquals(listOf(0.0, 4.0, 7.0), boundaries.map { it.quarterBeat })
        assertEquals(6, boundaries[1].numerator)
        assertEquals(2, boundaries[1].bar)
        assertEquals(2, ScoreNavigator.barBeatAt(s, 4.0).bar)
        assertEquals(3, ScoreNavigator.barBeatAt(s, 7.0).bar)
        assertEquals(listOf(MeasureBoundary(4.0, 2, 6, 8)), ScoreNavigator.measureBoundaries(s, 4.0, 4.0))
    }

    @Test fun firstMeterEventAfterZeroKeepsImplicitDefaultSegment() {
        val s = score(meters = listOf(TimeSignatureEvent(2400, 3, 4)))
        assertEquals(
            listOf(
                MeasureBoundary(0.0, 1, 4, 4),
                MeasureBoundary(4.0, 2, 4, 4),
                MeasureBoundary(5.0, 3, 3, 4),
            ),
            ScoreNavigator.measureBoundaries(s, 0.0, 5.0),
        )
        for ((beat, bar, numerator, denominator) in listOf(
            Quadruple(0.0, 1, 4, 4), Quadruple(4.0, 2, 4, 4),
            Quadruple(4.999, 2, 4, 4), Quadruple(5.0, 3, 3, 4),
        )) {
            val actual = ScoreNavigator.barBeatAt(s, beat)
            assertEquals(bar, actual.bar)
            assertEquals(numerator, actual.numerator)
            assertEquals(denominator, actual.denominator)
        }
    }

    @Test fun measureBoundaryQueryHasNoArbitraryCountCapAndSkipsToFarRange() {
        val s = score(meters = listOf(TimeSignatureEvent(0, 4, 4)))
        val many = ScoreNavigator.measureBoundaries(s, 0.0, 40004.0)
        assertEquals(10002, many.size)
        assertEquals(MeasureBoundary(40004.0, 10002, 4, 4), many.last())
        assertEquals(
            listOf(MeasureBoundary(40000.0, 10001, 4, 4)),
            ScoreNavigator.measureBoundaries(s, 39999.0, 40001.0),
        )
    }

    private fun assertBoundaries(meters: List<TimeSignatureEvent>, expected: List<Double>) {
        val s = score(meters = meters)
        val boundaries = ScoreNavigator.measureBoundaries(s, 0.0, expected.last())
        assertEquals(expected, boundaries.map { it.quarterBeat })
        boundaries.forEach {
            val barBeat = ScoreNavigator.barBeatAt(s, it.quarterBeat)
            assertEquals(it.bar, barBeat.bar)
            assertEquals(it.numerator, barBeat.numerator)
            assertEquals(it.denominator, barBeat.denominator)
        }
    }

    private data class Quadruple(
        val beat: Double, val bar: Int, val numerator: Int, val denominator: Int,
    )
}
