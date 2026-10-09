package com.einhander.temposcore.ui

import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.score.AccidentalPreference
import com.einhander.temposcore.score.NoteNaming
import com.einhander.temposcore.score.NoteSpelling
import com.einhander.temposcore.score.groupNotesByStartTick
import com.einhander.temposcore.score.keySignatureAt
import com.einhander.temposcore.score.pitchName
import com.einhander.temposcore.score.spellMidiPitch

/** Resolve at Note On once, including names for both supported naming styles. */
class NoteSpellingCache(score: MidiScore, notes: List<MidiNote>, fallback: AccidentalPreference) {
    private data class Entry(val spelling: NoteSpelling, val letters: String, val solfege: String)

    private val entries = groupNotesByStartTick(score, notes).associate { group ->
        val key = keySignatureAt(score, group.startTick)
        group.startTick to group.uniquePitches.associateWith { pitch ->
            val spelling = spellMidiPitch(pitch, key, fallback)
            Entry(spelling, pitchName(spelling, NoteNaming.Letters), pitchName(spelling, NoteNaming.Solfege))
        }
    }

    fun spelling(pitch: Int, startTick: Long): NoteSpelling = entries.getValue(startTick).getValue(pitch).spelling

    fun name(pitch: Int, startTick: Long, naming: NoteNaming): String {
        val entry = entries.getValue(startTick).getValue(pitch)
        return if (naming == NoteNaming.Solfege) entry.solfege else entry.letters
    }

    fun name(note: MidiNote, naming: NoteNaming): String = name(note.pitch, note.startTick, naming)
}
