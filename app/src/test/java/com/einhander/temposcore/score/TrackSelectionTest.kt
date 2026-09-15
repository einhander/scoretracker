package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.midi.MidiTrackInfo
import com.einhander.temposcore.midi.TempoEvent
import com.einhander.temposcore.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSelectionTest {
    private val score = MidiScore(
        format = 1,
        ppq = 480,
        notes = listOf(
            MidiNote(0, 0, 60, 100, 0, 480),
            MidiNote(1, 1, 72, 100, 960, 1440),
        ),
        tempoMap = listOf(TempoEvent(0, 500000)),
        timeSignatures = listOf(TimeSignatureEvent(0, 4, 4)),
        totalTicks = 1440,
        tracks = listOf(
            MidiTrackInfo(0, "Piano", 1, 60, 60, listOf(0)),
            MidiTrackInfo(1, null, 1, 72, 72, listOf(1)),
        ),
    )

    @Test
    fun visibleNotesFiltersByTrack() {
        assertEquals(2, score.visibleNotes(TrackSelection.All).size)
        assertEquals(listOf(60), score.visibleNotes(TrackSelection.Track(0)).map { it.pitch })
        assertEquals(listOf(72), score.visibleNotes(TrackSelection.Track(1)).map { it.pitch })
        assertEquals(emptyList<MidiNote>(), score.visibleNotes(TrackSelection.Track(99)))
    }

    @Test
    fun navigatorUsesFilteredNotes() {
        assertEquals(listOf(60), ScoreNavigator.soundingNotes(score, score.visibleNotes(TrackSelection.Track(0)), 0.5).map { it.pitch })
        assertEquals(emptyList<MidiNote>(), ScoreNavigator.soundingNotes(score, score.visibleNotes(TrackSelection.Track(1)), 0.5))
        assertEquals(listOf(72), ScoreNavigator.nextNotes(score, score.visibleNotes(TrackSelection.Track(1)), 0.5).map { it.pitch })
        assertEquals(emptyList<MidiNote>(), ScoreNavigator.nextNotes(score, score.visibleNotes(TrackSelection.Track(0)), 0.5))
    }

    @Test
    fun formatsTrackLabels() {
        assertEquals("Track 1 — Piano • 12 notes, C4–C5", ScoreNavigator.trackLabel(MidiTrackInfo(0, "Piano", 12, 60, 72, listOf(0))))
        assertEquals("Track 2 • 5 notes, E4–G4", ScoreNavigator.trackLabel(MidiTrackInfo(1, null, 5, 64, 67, listOf(1))))
        assertEquals("Track 3 — Drums • no notes", ScoreNavigator.trackLabel(MidiTrackInfo(2, "Drums", 0, null, null, listOf(9))))
        assertEquals("Track 4 • no notes", ScoreNavigator.trackLabel(MidiTrackInfo(3, "  ", 0, null, null, emptyList())))
    }
}
