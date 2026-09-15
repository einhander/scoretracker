package com.einhander.temposcore.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiFileParserTest {
    @Test
    fun parsesTempoSignatureAndQuarterNote() {
        val bytes = hex(
            "4D546864000000060000000101E0" +
                "4D54726B0000001C" +
                "00FF510307A120" +
                "00FF580404021808" +
                "00903C64" +
                "8360803C40" +
                "00FF2F00"
        )

        val score = MidiFileParser.parse(bytes)
        assertEquals(480, score.ppq)
        assertEquals(120.0, score.initialBpm, 0.001)
        assertEquals(4, score.timeSignatures.first().numerator)
        assertEquals(4, score.timeSignatures.first().denominator)
        assertEquals(1, score.notes.size)
        assertEquals(60, score.notes.first().pitch)
        assertEquals(480L, score.notes.first().durationTicks)
        assertTrue(score.totalTicks >= 480L)
    }

    @Test
    fun parsesMultipleTracksAndTrackMetadata() {
        val bytes = hex(
            "4D546864000000060001000201E0" +
                "4D54726B00000016" +
                "00FF03055069616E6F" +
                "00903C64" +
                "8360803C40" +
                "00FF2F00" +
                "4D54726B0000000D" +
                "00914864" +
                "8360814840" +
                "00FF2F00"
        )

        val score = MidiFileParser.parse(bytes)
        assertEquals(1, score.format)
        assertEquals(480, score.ppq)
        assertEquals(2, score.tracks.size)
        assertEquals("Piano", score.tracks[0].name)
        assertEquals(null, score.tracks[1].name)
        assertEquals(1, score.tracks[0].noteCount)
        assertEquals(1, score.tracks[1].noteCount)
        assertEquals(60, score.tracks[0].pitchMin)
        assertEquals(60, score.tracks[0].pitchMax)
        assertEquals(72, score.tracks[1].pitchMin)
        assertEquals(72, score.tracks[1].pitchMax)
        assertEquals(listOf(0), score.tracks[0].channels)
        assertEquals(listOf(1), score.tracks[1].channels)
        assertEquals(2, score.notes.size)
        assertTrue(score.notes.any { it.track == 0 && it.pitch == 60 })
        assertTrue(score.notes.any { it.track == 1 && it.pitch == 72 })
    }

    @Test
    fun fallsBackToIso88591ForInvalidUtf8TrackName() {
        val score = MidiFileParser.parse(hex(
            "4D546864000000060000000101E0" +
                "4D54726B00000013" + "00FF0302E941" + "00903C64" +
                "8360803C40" + "00FF2F00"
        ))
        assertEquals("éA", score.tracks[0].name)
    }

    @Test
    fun firstTrackNameWins() {
        val score = MidiFileParser.parse(hex(
            "4D546864000000060000000101E0" +
                "4D54726B00000017" + "00FF030141" + "00FF030142" +
                "00903C64" + "8360803C40" + "00FF2F00"
        ))
        assertEquals("A", score.tracks[0].name)
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0)
        return ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
