package com.einhander.temposcore.score

import com.einhander.temposcore.midi.KeySignatureEvent
import com.einhander.temposcore.midi.MidiNote
import com.einhander.temposcore.midi.MidiScore
import com.einhander.temposcore.midi.MidiTrackInfo
import com.einhander.temposcore.midi.TempoEvent
import com.einhander.temposcore.midi.TimeSignatureEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchSpellingTest {
    @Test
    fun allMidiPitchesReconstructForEveryMajorAndMinorSignature() {
        for (sf in -7..7) for (minor in listOf(false, true)) {
            val key = KeySignatureEvent(0, sf, minor)
            for (midiPitch in 0..127) {
                val spelling = spellMidiPitch(midiPitch, key)
                val naturalPc = intArrayOf(0, 2, 4, 5, 7, 9, 11)[spelling.letter.ordinal]
                assertEquals("sf=$sf minor=$minor pitch=$midiPitch spelling=$spelling", midiPitch,
                    (spelling.octave + 1) * 12 + naturalPc + spelling.accidental)
                assertTrue(spelling.accidental in -1..1)
                assertEquals(spelling.octave * 7 + spelling.letter.ordinal - (4 * 7 + NoteLetter.B.ordinal),
                    spelling.staffStepFromB4)
            }
        }
    }

    @Test
    fun noKeyUsesRequestedChromaticFallbackAndNaturalNotesStayNatural() {
        assertEquals(NoteSpelling(NoteLetter.C, 1, 4, -6), spellMidiPitch(61, null))
        assertEquals(NoteSpelling(NoteLetter.D, -1, 4, -5), spellMidiPitch(61, null, AccidentalPreference.Flats))
        assertEquals(0, spellMidiPitch(60, null, AccidentalPreference.Flats).accidental)
    }

    @Test
    fun tonalKeysPreferTheirDiatonicEnharmonicSpelling() {
        assertEquals(NoteLetter.C, spellMidiPitch(61, KeySignatureEvent(0, 7, false)).letter)
        assertEquals(1, spellMidiPitch(61, KeySignatureEvent(0, 7, false)).accidental)
        assertEquals(NoteLetter.D, spellMidiPitch(61, KeySignatureEvent(0, -5, false)).letter)
        assertEquals(-1, spellMidiPitch(61, KeySignatureEvent(0, -5, false)).accidental)
        // Explicit C major/A minor key signatures use naturals when possible;
        // chromatic tie choice is sharp by documented zero-signature policy.
        assertEquals(NoteLetter.C, spellMidiPitch(61, KeySignatureEvent(0, 0, false)).letter)
        assertEquals(1, spellMidiPitch(61, KeySignatureEvent(0, 0, true)).accidental)
    }

    @Test
    fun sevenAccidentalKeysSpellBoundaryEnharmonicsWithCorrectOctaves() {
        val bSharp = spellMidiPitch(60, KeySignatureEvent(0, 7, false))
        assertEquals(NoteLetter.B, bSharp.letter)
        assertEquals(1, bSharp.accidental)
        assertEquals(3, bSharp.octave)
        val cFlat = spellMidiPitch(59, KeySignatureEvent(0, -7, false))
        assertEquals(NoteLetter.C, cFlat.letter)
        assertEquals(-1, cFlat.accidental)
        assertEquals(4, cFlat.octave)
    }

    @Test
    fun namesSupportLetterAndSolfegeAndKeyChangesAtNoteStart() {
        val score = scoreWithKeys(
            listOf(KeySignatureEvent(0, 7, false), KeySignatureEvent(480, -5, false)),
        )
        val c4 = MidiNote(0, 0, 60, 90, 0, 120)
        val cSharp4 = MidiNote(0, 0, 61, 90, 120, 240)
        val dFlat4 = MidiNote(0, 0, 61, 90, 480, 600)
        assertEquals("B♯3", ScoreNavigator.pitchName(score, c4))
        assertEquals("C♯4", ScoreNavigator.pitchName(score, cSharp4))
        assertEquals("D♭4", ScoreNavigator.pitchName(score, dFlat4))
        assertEquals("До♯4", pitchName(spellMidiPitch(61, KeySignatureEvent(0, 7, false)), NoteNaming.Solfege))
        // Existing textual API remains backward-compatible ASCII sharp names.
        assertEquals("C#4", ScoreNavigator.pitchName(61, NoteNaming.Letters))
    }

    @Test
    fun keyLookupUsesLastSameTickInputEventAndChangesOnlyAtTick() {
        val first = KeySignatureEvent(100, 1, false)
        val lastSameTick = KeySignatureEvent(100, -2, true)
        val score = scoreWithKeys(listOf(KeySignatureEvent(20, 3, false), first, lastSameTick))
        assertEquals(null, keySignatureAt(score, 19))
        assertEquals(3, keySignatureAt(score, 20)?.sharpsFlats)
        assertEquals(lastSameTick, keySignatureAt(score, 100))
        assertEquals(lastSameTick, keySignatureAt(score, 101))
    }

    private fun scoreWithKeys(keys: List<KeySignatureEvent>) = MidiScore(
        format = 0,
        ppq = 480,
        notes = emptyList(),
        tempoMap = listOf(TempoEvent(0, 500_000)),
        timeSignatures = listOf(TimeSignatureEvent(0, 4, 4)),
        totalTicks = 1920,
        tracks = listOf(MidiTrackInfo(0, null, 0, null, null, emptyList())),
        keySignatures = keys,
    )
}
