package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.midi.MidiTrackInfo
import kotlin.math.floor

object ScoreNavigator {
    data class BarBeat(val bar: Int, val beat: Double, val numerator: Int, val denominator: Int)

    fun soundingNotes(score: MidiScore, quarterBeat: Double): List<MidiNote> =
        soundingNotes(score, score.notes, quarterBeat)

    fun soundingNotes(score: MidiScore, notes: List<MidiNote>, quarterBeat: Double): List<MidiNote> =
        notes.filter {
            val start = score.noteStartBeat(it)
            val end = score.noteEndBeat(it)
            start <= quarterBeat && quarterBeat < end
        }

    fun nextNotes(score: MidiScore, quarterBeat: Double, tolerance: Double = 1e-6): List<MidiNote> {
        return nextNotes(score, score.notes, quarterBeat, tolerance)
    }

    fun nextNotes(score: MidiScore, notes: List<MidiNote>, quarterBeat: Double, tolerance: Double = 1e-6): List<MidiNote> {
        val nextStart = notes
            .asSequence()
            .map { score.noteStartBeat(it) }
            .filter { it > quarterBeat + tolerance }
            .minOrNull() ?: return emptyList()
        return notes.filter { kotlin.math.abs(score.noteStartBeat(it) - nextStart) <= tolerance }
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

    fun trackLabel(track: MidiTrackInfo): String {
        var label = "Track ${track.index + 1}"
        if (!track.name.isNullOrBlank()) label += " — ${track.name}"
        label += if (track.pitchMin != null && track.pitchMax != null) {
            " • ${track.noteCount} notes, ${pitchName(track.pitchMin)}–${pitchName(track.pitchMax)}"
        } else " • no notes"
        return label
    }

    fun noteList(notes: List<MidiNote>): String =
        if (notes.isEmpty()) "—" else notes.joinToString(" ") { pitchName(it.pitch) }

    // --- Tempo map (spec §26) -------------------------------------------------

    /** BPM in force at the given quarter-beat position (last tempo event at or before it). */
    fun tempoAtQuarterBeat(score: MidiScore, position: Double): Double {
        val tick = (position * score.ppq).toLong()
        var bpm = score.initialBpm
        for (event in score.tempoMap.sortedBy { it.tick }) {
            if (event.tick <= tick) bpm = event.bpm else break
        }
        return bpm
    }

    /** Nominal (reference) seconds for a quarter-beat position, integrating the tempo map. */
    fun quarterBeatToSeconds(score: MidiScore, position: Double): Double {
        if (position <= 0.0) return 0.0
        val targetTick = position * score.ppq
        var seconds = 0.0
        var prevTick = 0L
        var prevBpm = score.initialBpm
        for (event in score.tempoMap.sortedBy { it.tick }) {
            if (event.tick >= targetTick) break
            val prevQuarter = score.tickToQuarterBeats(prevTick)
            val eventQuarter = score.tickToQuarterBeats(event.tick)
            seconds += (eventQuarter - prevQuarter) * 60.0 / prevBpm
            prevTick = event.tick
            prevBpm = event.bpm
        }
        val prevQuarter = score.tickToQuarterBeats(prevTick)
        seconds += (position - prevQuarter) * 60.0 / prevBpm
        return seconds
    }

    /** Quarter-beat position for a nominal (reference) seconds value (inverse of [quarterBeatToSeconds]). */
    fun secondsToQuarterBeat(score: MidiScore, seconds: Double): Double {
        if (seconds <= 0.0) return 0.0
        var remaining = seconds
        var prevTick = 0L
        var prevBpm = score.initialBpm
        for (event in score.tempoMap.sortedBy { it.tick }) {
            val prevQuarter = score.tickToQuarterBeats(prevTick)
            val eventQuarter = score.tickToQuarterBeats(event.tick)
            val segmentSeconds = (eventQuarter - prevQuarter) * 60.0 / prevBpm
            if (remaining <= segmentSeconds) return prevQuarter + remaining * prevBpm / 60.0
            remaining -= segmentSeconds
            prevTick = event.tick
            prevBpm = event.bpm
        }
        val prevQuarter = score.tickToQuarterBeats(prevTick)
        return prevQuarter + remaining * prevBpm / 60.0
    }
}
