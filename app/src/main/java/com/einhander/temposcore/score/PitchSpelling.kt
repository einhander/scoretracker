package com.einhander.temposcore.score

import com.einhander.temposcore.midi.KeySignatureEvent
import com.einhander.temposcore.midi.MidiScore
import java.lang.Math.floorDiv

enum class AccidentalPreference { Sharps, Flats }

enum class NoteLetter { C, D, E, F, G, A, B }

data class NoteSpelling(
    val letter: NoteLetter,
    /** -1 flat, 0 natural, +1 sharp. */
    val accidental: Int,
    val octave: Int,
    /** Diatonic steps relative to B4 staff origin. */
    val staffStepFromB4: Int,
)

private val naturalPitchClasses = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val sharpOrder = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
private val flatOrder = intArrayOf(6, 2, 5, 1, 4, 0, 3) // B E A D G C F

/** Return last key signature at or before [tick]. No artificial C-major event is added. */
fun keySignatureAt(score: MidiScore, tick: Long): KeySignatureEvent? =
    score.keySignatures.withIndex()
        .filter { it.value.tick <= tick }
        .maxWithOrNull(compareBy<IndexedValue<KeySignatureEvent>> { it.value.tick }.thenBy { it.index })
        ?.value

/**
 * Choose a conventional single accidental spelling which exactly reconstructs
 * the MIDI pitch. Key-signature spelling wins; otherwise key polarity selects
 * chromatic fallback. An explicit zero-signature key uses sharps for chromatic
 * fallback, unless caller supplies a fallback only when no key is present.
 */
fun spellMidiPitch(
    pitch: Int,
    keySignature: KeySignatureEvent?,
    fallback: AccidentalPreference = AccidentalPreference.Sharps,
): NoteSpelling {
    val midiPitch = pitch.coerceIn(0, 127)
    val pitchClass = midiPitch % 12
    val keyAlterations = keySignature?.let(::keyAlterations)
    val effectivePreference = when {
        keySignature == null -> fallback
        keySignature.sharpsFlats < 0 -> AccidentalPreference.Flats
        else -> AccidentalPreference.Sharps
    }

    data class Candidate(val letter: NoteLetter, val accidental: Int, val octave: Int, val keyMatch: Boolean)
    val candidates = buildList {
        for (letter in NoteLetter.entries) {
            for (accidental in -1..1) {
                val absoluteSemitone = midiPitch - naturalPitchClasses[letter.ordinal] - accidental
                if (absoluteSemitone % 12 != 0) continue
                val octave = floorDiv(absoluteSemitone, 12) - 1
                add(Candidate(letter, accidental, octave, keyAlterations != null && keyAlterations[letter.ordinal] == accidental))
            }
        }
    }
    check(candidates.isNotEmpty()) { "No single-accidental spelling for pitch $midiPitch (pc=$pitchClass)" }

    val selected = candidates.minWithOrNull(compareByDescending<Candidate> { it.keyMatch }
        .thenBy { if (it.accidental == 0) 0 else 1 }
        .thenBy {
            when (effectivePreference) {
                AccidentalPreference.Sharps -> if (it.accidental < 0) 1 else 0
                AccidentalPreference.Flats -> if (it.accidental > 0) 1 else 0
            }
        }
        .thenBy { it.letter.ordinal })!!
    val step = selected.octave * NoteLetter.entries.size + selected.letter.ordinal -
        (4 * NoteLetter.entries.size + NoteLetter.B.ordinal)
    return NoteSpelling(selected.letter, selected.accidental, selected.octave, step)
}

fun pitchName(spelling: NoteSpelling, naming: NoteNaming): String {
    val letter = when (naming) {
        NoteNaming.Letters -> spelling.letter.name
        NoteNaming.Solfege -> when (spelling.letter) {
            NoteLetter.C -> "До"
            NoteLetter.D -> "Ре"
            NoteLetter.E -> "Ми"
            NoteLetter.F -> "Фа"
            NoteLetter.G -> "Соль"
            NoteLetter.A -> "Ля"
            NoteLetter.B -> "Си"
        }
    }
    val accidental = when (spelling.accidental) {
        -1 -> "♭"
        0 -> ""
        1 -> "♯"
        else -> error("Accidental must be -1, 0, or 1")
    }
    return "$letter$accidental${spelling.octave}"
}

private fun keyAlterations(key: KeySignatureEvent): IntArray {
    val alterations = IntArray(NoteLetter.entries.size)
    val order = if (key.sharpsFlats >= 0) sharpOrder else flatOrder
    val amount = kotlin.math.abs(key.sharpsFlats)
    for (i in 0 until amount) alterations[order[i]] = if (key.sharpsFlats > 0) 1 else -1
    return alterations
}
