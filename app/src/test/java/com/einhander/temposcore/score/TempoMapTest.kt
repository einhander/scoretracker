package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.midi.TempoEvent
import com.einhander.temposcore.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class TempoMapTest {
    // 120 BPM (500000 us/quarter) for the first bar, then 160 BPM (375000) from beat 1.
    private val ppq = 480
    private val score = MidiScore(
        format = 1,
        ppq = ppq,
        notes = emptyList(),
        tempoMap = listOf(TempoEvent(0, 500000), TempoEvent(480, 375000)),
        timeSignatures = listOf(TimeSignatureEvent(0, 4, 4)),
        totalTicks = 1920,
        tracks = emptyList(),
    )

    @Test
    fun tempoAtQuarterBeatTracksRegion() {
        assertEquals(120.0, ScoreNavigator.tempoAtQuarterBeat(score, 0.0), 1e-9)
        assertEquals(120.0, ScoreNavigator.tempoAtQuarterBeat(score, 0.5), 1e-9)
        assertEquals(160.0, ScoreNavigator.tempoAtQuarterBeat(score, 1.0), 1e-9)
        assertEquals(160.0, ScoreNavigator.tempoAtQuarterBeat(score, 3.5), 1e-9)
    }

    @Test
    fun quarterBeatToSecondsIntegratesTempoMap() {
        // 1 quarter at 120 BPM = 0.5 s.
        assertEquals(0.5, ScoreNavigator.quarterBeatToSeconds(score, 1.0), 1e-9)
        // +1 quarter at 160 BPM = 0.375 s -> 0.875 s total.
        assertEquals(0.875, ScoreNavigator.quarterBeatToSeconds(score, 2.0), 1e-9)
        assertEquals(0.0, ScoreNavigator.quarterBeatToSeconds(score, 0.0), 1e-9)
    }

    @Test
    fun secondsToQuarterBeatIsInverse() {
        assertEquals(1.0, ScoreNavigator.secondsToQuarterBeat(score, 0.5), 1e-9)
        assertEquals(2.0, ScoreNavigator.secondsToQuarterBeat(score, 0.875), 1e-9)
        assertEquals(0.0, ScoreNavigator.secondsToQuarterBeat(score, 0.0), 1e-9)
        // Round-trip across the tempo boundary.
        val beat = 1.7
        assertEquals(beat, ScoreNavigator.secondsToQuarterBeat(score, ScoreNavigator.quarterBeatToSeconds(score, beat)), 1e-6)
    }

    @Test
    fun constantTempoRoundTrip() {
        val constant = MidiScore(
            format = 1,
            ppq = ppq,
            notes = emptyList(),
            tempoMap = listOf(TempoEvent(0, 500000)),
            timeSignatures = listOf(TimeSignatureEvent(0, 4, 4)),
            totalTicks = 1920,
            tracks = emptyList(),
        )
        // 120 BPM: 1 quarter = 0.5 s.
        assertEquals(0.5, ScoreNavigator.quarterBeatToSeconds(constant, 1.0), 1e-9)
        assertEquals(4.0, ScoreNavigator.secondsToQuarterBeat(constant, 2.0), 1e-9)
        for (beat in listOf(0.0, 0.3, 1.0, 2.5, 10.0)) {
            val rt = ScoreNavigator.secondsToQuarterBeat(constant, ScoreNavigator.quarterBeatToSeconds(constant, beat))
            assertEquals(beat, rt, 1e-6)
        }
    }
}
