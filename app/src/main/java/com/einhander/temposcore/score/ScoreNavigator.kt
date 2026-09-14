package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import kotlin.math.floor

object ScoreNavigator {
    data class BarBeat(val bar: Int, val beat: Double, val numerator: Int, val denominator: Int)

    fun soundingNotes(score: MidiScore, quarterBeat: Double): List<MidiNote> =
        score.notes.filter {
            val start = score.noteStartBeat(it)
            val end = score.noteEndBeat(it)
            start <= quarterBeat && quarterBeat < end
        }

    fun nextNotes(score: MidiScore, quarterBeat: Double, tolerance: Double = 1e-6): List<MidiNote> {
        val nextStart = score.notes
            .asSequence()
            .map { score.noteStartBeat(it) }
            .filter { it > quarterBeat + tolerance }
            .minOrNull() ?: return emptyList()
        return score.notes.filter { kotlin.math.abs(score.noteStartBeat(it) - nextStart) <= tolerance }
    }

    fun barBeatAt(score: MidiScore, quarterBeat: Double): BarBeat {
        val events = score.timeSignatures.sortedBy { it.tick }
        var accumulatedBars = 0
        var segmentStartQuarter = 0.0
        var numerator = 4
        var denominator = 4

        for (event in events) {
            val eventQuarter = score.tickToQuarterBeats(event.tick)
            if (eventQuarter > quarterBeat) break
            if (eventQuarter > segmentStartQuarter) {
                val oldBarLength = numerator * 4.0 / denominator
                accumulatedBars += floor((eventQuarter - segmentStartQuarter) / oldBarLength + 1e-9).toInt()
            }
            segmentStartQuarter = eventQuarter
            numerator = event.numerator
            denominator = event.denominator
        }

        val barLengthQuarter = numerator * 4.0 / denominator
        val notatedBeatQuarter = 4.0 / denominator
        val offset = (quarterBeat - segmentStartQuarter).coerceAtLeast(0.0)
        val barsIntoSegment = floor(offset / barLengthQuarter + 1e-9).toInt()
        val withinBarQuarter = offset - barsIntoSegment * barLengthQuarter
        val notatedBeat = withinBarQuarter / notatedBeatQuarter + 1.0
        return BarBeat(
            bar = accumulatedBars + barsIntoSegment + 1,
            beat = notatedBeat,
            numerator = numerator,
            denominator = denominator,
        )
    }

    fun pitchName(pitch: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val p = pitch.coerceIn(0, 127)
        return names[p % 12] + (p / 12 - 1)
    }

    fun noteList(notes: List<MidiNote>): String =
        if (notes.isEmpty()) "—" else notes.joinToString(" ") { pitchName(it.pitch) }
}
