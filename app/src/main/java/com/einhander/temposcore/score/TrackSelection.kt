package com.einhander.temposcore.score

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore

sealed class TrackSelection {
    data object All : TrackSelection()
    data class Track(val index: Int) : TrackSelection()
}

fun MidiScore.visibleNotes(selection: TrackSelection): List<MidiNote> = when (selection) {
    TrackSelection.All -> notes
    is TrackSelection.Track -> notes.filter { it.track == selection.index }
}
