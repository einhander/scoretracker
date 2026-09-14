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

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0)
        return ByteArray(text.length / 2) { i ->
            text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
