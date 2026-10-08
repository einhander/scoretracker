package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.midi.MidiTrackInfo
import kotlin.math.floor

data class MeasureBoundary(val quarterBeat: Double, val bar: Int, val numerator: Int, val denominator: Int)

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
        val segments = meterSegments(score)
        val segment = segments.lastOrNull { it.start <= quarterBeat } ?: segments.first()
        val offset = (quarterBeat - segment.start).coerceAtLeast(0.0)
        val barsIntoSegment = floor(offset / segment.barLength + 1e-9).toInt()
        val withinBarQuarter = offset - barsIntoSegment * segment.barLength
        val notatedBeat = withinBarQuarter / (4.0 / segment.denominator) + 1.0
        return BarBeat(
            bar = segment.firstBar + barsIntoSegment,
            beat = notatedBeat,
            numerator = segment.numerator,
            denominator = segment.denominator,
        )
    }

    /** Meter change starts a fresh bar; any preceding partial bar counts as one. */
    fun measureBoundaries(score: MidiScore, fromBeat: Double, toBeat: Double): List<MeasureBoundary> {
        if (fromBeat > toBeat) return emptyList()
        val segments = meterSegments(score)
        val result = ArrayList<MeasureBoundary>()
        for (index in segments.indices) {
            val segment = segments[index]
            if (segment.start > toBeat) break
            val segmentEnd = segments.getOrNull(index + 1)?.start ?: Double.POSITIVE_INFINITY
            val firstN = maxOf(0, floor((fromBeat - segment.start) / segment.barLength + 1e-9).toInt())
            val lastN = floor((minOf(toBeat, segmentEnd) - segment.start) / segment.barLength + 1e-9).toInt()
            if (lastN < firstN) continue
            for (n in firstN..lastN) {
                val beat = segment.start + n * segment.barLength
                if (beat >= fromBeat - 1e-9 && (index == segments.lastIndex || beat < segmentEnd - 1e-9)) {
                    result += MeasureBoundary(beat, segment.firstBar + n, segment.numerator, segment.denominator)
                }
            }
        }
        return result
    }

    private data class MeterSegment(
        val start: Double, val firstBar: Int, val numerator: Int, val denominator: Int,
    ) {
        val barLength: Double get() = numerator * 4.0 / denominator
    }

    private fun meterSegments(score: MidiScore): List<MeterSegment> {
        // Stable sort preserves event order; last event at duplicate tick wins.
        val events = score.timeSignatures.withIndex().sortedWith(compareBy({ it.value.tick }, { it.index }))
        val segments = arrayListOf(MeterSegment(0.0, 1, 4, 4))
        var start = 0.0
        var bar = 1
        var numerator = 4
        var denominator = 4
        for ((_, event) in events) {
            val eventBeat = score.tickToQuarterBeats(event.tick)
            if (eventBeat < 0.0) continue
            if (eventBeat > start) {
                val oldLength = numerator * 4.0 / denominator
                val completeBars = floor((eventBeat - start) / oldLength + 1e-9).toInt()
                bar += maxOf(1, if (completeBars * oldLength < eventBeat - start - 1e-9) completeBars + 1 else completeBars)
                start = eventBeat
            }
            numerator = event.numerator
            denominator = event.denominator
            if (segments.last().start == start) segments.removeAt(segments.lastIndex)
            segments += MeterSegment(start, bar, numerator, denominator)
        }
        return segments.ifEmpty { listOf(MeterSegment(0.0, 1, 4, 4)) }
    }

    fun pitchName(pitch: Int, naming: NoteNaming = NoteNaming.Letters): String {
        val letterNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val solfegeNames = arrayOf("До", "До♯", "Ре", "Ре♯", "Ми", "Фа", "Фа♯", "Соль", "Соль♯", "Ля", "Ля♯", "Си")
        val p = pitch.coerceIn(0, 127)
        val names = if (naming == NoteNaming.Solfege) solfegeNames else letterNames
        return names[p % 12] + (p / 12 - 1)
    }

    fun trackLabel(track: MidiTrackInfo, naming: NoteNaming = NoteNaming.Letters): String {
        var label = "Track ${track.index + 1}"
        if (!track.name.isNullOrBlank()) label += " — ${track.name}"
        label += if (track.pitchMin != null && track.pitchMax != null) {
            " • ${track.noteCount} notes, ${pitchName(track.pitchMin, naming)}–${pitchName(track.pitchMax, naming)}"
        } else " • no notes"
        return label
    }

    fun noteList(notes: List<MidiNote>, naming: NoteNaming = NoteNaming.Letters): String =
        if (notes.isEmpty()) "—" else notes.joinToString(" ") { pitchName(it.pitch, naming) }

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
