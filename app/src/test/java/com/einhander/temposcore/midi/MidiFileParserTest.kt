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

    @Test
    fun keySignaturesAreValidatedGlobalAndLastValidSameTickWins() {
        val track0 = byteArrayOf(
            0, 0xff.toByte(), 0x59, 2, 1, 0, // C# major
            10, 0xff.toByte(), 0x59, 2, 0xf8.toByte(), 0, // invalid sf -8
            0, 0xff.toByte(), 0x59, 2, 1, 2, // invalid mode
            0, 0xff.toByte(), 0x59, 1, 0, // invalid length, payload still skipped
            0, 0xff.toByte(), 0x59, 3, 0xfe.toByte(), 0, 0, // invalid length
            0, 0xff.toByte(), 0x59, 2, 0xfe.toByte(), 1, // valid D-flat minor at tick 10
            0, 0x90.toByte(), 60, 100, // parser remains aligned after malformed events
            10, 0x80.toByte(), 60, 0,
            0, 0xff.toByte(), 0x2f, 0,
        )
        val track1 = byteArrayOf(
            0, 0xff.toByte(), 0x59, 2, 4, 0, // later input track wins tick zero
            0, 0xff.toByte(), 0x2f, 0,
        )
        val score = MidiFileParser.parse(smf(format = 1, tracks = listOf(track0, track1)))
        assertEquals(listOf(KeySignatureEvent(0, 4, false), KeySignatureEvent(10, -2, true)), score.keySignatures)
        assertEquals(1, score.notes.size)
        assertEquals(60, score.notes.single().pitch)
    }

    @Test
    fun malformedAndAbsentKeySignaturesDoNotInventKey() {
        val score = MidiFileParser.parse(smf(format = 0, listOf(byteArrayOf(
            0, 0xff.toByte(), 0x59, 2, 8, 0,
            0, 0xff.toByte(), 0x59, 2, 0, 3,
            0, 0xff.toByte(), 0x2f, 0,
        ))))
        assertTrue(score.keySignatures.isEmpty())
        assertTrue(MidiFileParser.parse(smf(format = 0, listOf(byteArrayOf(0, 0xff.toByte(), 0x2f, 0))))
            .keySignatures.isEmpty())
    }

    private fun smf(format: Int, tracks: List<ByteArray>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        output.write(hex("4D5468640000000600${format.toString(16).padStart(2, '0')}${tracks.size.toString(16).padStart(4, '0')}01E0"))
        tracks.forEach { track ->
            output.write(hex("4D54726B"))
            val size = track.size
            output.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte()))
            output.write(track)
        }
        return output.toByteArray()
    }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0)
        return ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
