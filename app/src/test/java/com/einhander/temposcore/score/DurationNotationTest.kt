package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

class DurationNotationTest {
    @Test fun standardAndDottedDurationsMapToConventionalHeadsStemsAndFlags() {
        for (denominator in listOf(1, 2, 4, 8, 16, 32, 64)) {
            for (dots in 0..2) {
                val beats = 4.0 / denominator * when (dots) { 1 -> 1.5; 2 -> 1.75; else -> 1.0 }
                val glyph = durationGlyph(beats)
                assertEquals(denominator, glyph.denominator)
                assertEquals(dots, glyph.dots)
                assertFalse(glyph.approximate)
                assertEquals(denominator <= 2, glyph.hollow)
                assertEquals(denominator != 1, glyph.hasStem)
                assertEquals(when (denominator) { 8 -> 1; 16 -> 2; 32 -> 3; 64 -> 4; else -> 0 }, glyph.flags)
            }
        }
    }

    @Test fun midiPpqAndUnquantizedIntervalsRemainExactRegardlessOfGlyph() {
        for (ppq in listOf(96, 480, 960)) {
            for (beats in listOf(4.0, 2.0, 1.0, 0.5, 0.25, 0.125, 0.0625, 1.5, 0.875)) {
                val ticks = (beats * ppq).roundToLong()
                val note = MidiNote(0, 0, 60, 90, 120L, 120L + ticks)
                val score = MidiScore(1, ppq, listOf(note), emptyList(), emptyList(), 10000L, emptyList())
                val interval = noteInterval(score, note)
                val glyph = durationGlyph(interval.endBeat - interval.startBeat)
                assertEquals(beats, glyph.quarterBeats, 1e-9)
                assertFalse(glyph.approximate)
                assertEquals(120.0 / ppq, interval.startBeat, 1e-9)
                assertEquals((120.0 + ticks) / ppq, interval.endBeat, 1e-9)
            }
            val interval = NoteInterval(11.0 / ppq, 148.0 / ppq)
            assertTrue(durationGlyph(interval.endBeat - interval.startBeat).approximate)
            assertEquals(137.0 / ppq, interval.endBeat - interval.startBeat, 1e-12)
        }
    }

    @Test fun approximationAndOutOfRangeValuesHaveBoundedDeterministicGlyphs() {
        assertEquals(DurationGlyph(4, 0, 1.0, true), durationGlyph(1.01))
        assertEquals(4, durationGlyph(sqrt(1.5)).denominator)
        assertEquals(0, durationGlyph(sqrt(1.5)).dots)
        for (value in listOf(0.0, -1.0, 0.001, Double.MIN_VALUE)) {
            assertEquals(DurationGlyph(64, 0, 0.0625, true), durationGlyph(value))
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(DurationGlyph(4, 0, 1.0, true), durationGlyph(value))
        }
        assertEquals(DurationGlyph(1, 2, 7.0, true), durationGlyph(100.0))
    }

    @Test fun fullSelectionExtentFitsUpAndDownStemsAndStaysIndependentOfViewport() {
        val glyph = durationGlyph(0.0625)
        val steps = listOf(-18, -1, 0, 1, 18)
        fun extent() = steps.fold(StaffExtent()) { current, step ->
            current.including(step, true, 0.2f, glyph.aboveHeadUnits(step), glyph.belowHeadUnits(step))
        }
        val full = extent()
        for (step in steps) {
            assertTrue(full.top >= step / 2f - 0.2f + glyph.aboveHeadUnits(step))
            assertTrue(full.bottom >= -step / 2f + 0.2f + glyph.belowHeadUnits(step))
        }
        assertEquals(4f, glyph.aboveHeadUnits(-1), 0f)
        assertEquals(4f, glyph.belowHeadUnits(0), 0f)
        assertTrue(glyph.stemLengthUnits - (1.85f + (glyph.flags - 1) * 0.45f) > 0.5f)
        assertEquals(full, extent())
        assertEquals(0.95f, durationGlyph(4.0).aboveHeadUnits(-18), 0f)
    }

    @Test fun pitchPaletteRepeatsAcrossOctavesAndEveryHueHasReadableWhiteContrast() {
        val colors = (0..11).map { pitchClassColor(it) }
        assertEquals(12, colors.distinct().size)
        for (pitch in -12..139) {
            assertEquals(pitchClassColor(pitch), pitchClassColor(pitch + 12))
            assertEquals(pitchClassColor(pitch, true), pitchClassColor(pitch + 12, true))
        }
        for (color in colors + (0..11).map { pitchClassColor(it, true) }) {
            assertEquals(255, color ushr 24)
            fun linear(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            val luminance = 0.2126 * linear((color shr 16) and 255) +
                0.7152 * linear((color shr 8) and 255) + 0.0722 * linear(color and 255)
            assertTrue("White contrast for ${color.toUInt().toString(16)}", 1.05 / (luminance + 0.05) >= 4.5)
        }
    }
}
