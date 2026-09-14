package com.einhander.temposcore.midi

import java.util.ArrayDeque

/**
 * Small Standard MIDI File (SMF) parser for the application scaffold.
 *
 * Supported now:
 * - SMF format 0 and 1
 * - PPQ timing (SMPTE division intentionally rejected)
 * - running status
 * - Note On / Note Off
 * - tempo meta events (0x51)
 * - time-signature meta events (0x58)
 * - SysEx skipping
 *
 * This is deliberately dependency-free so it can be unit-tested on the JVM.
 */
object MidiFileParser {
    fun parse(bytes: ByteArray): MidiScore {
        val c = Cursor(bytes)
        c.expectAscii("MThd")
        val headerLength = c.readU32().toInt()
        require(headerLength >= 6) { "Invalid MIDI header length: $headerLength" }

        val format = c.readU16()
        val trackCount = c.readU16()
        val division = c.readU16()
        require((division and 0x8000) == 0) {
            "SMPTE-timed MIDI files are not supported yet"
        }
        val ppq = division and 0x7fff
        require(ppq > 0) { "Invalid PPQ: $ppq" }
        if (headerLength > 6) c.skip(headerLength - 6)

        val notes = mutableListOf<MidiNote>()
        val tempos = mutableListOf<TempoEvent>()
        val signatures = mutableListOf<TimeSignatureEvent>()
        var totalTicks = 0L

        repeat(trackCount) { trackIndex ->
            c.expectAscii("MTrk")
            val trackLength = c.readU32().toInt()
            val trackEnd = c.position + trackLength
            require(trackEnd <= bytes.size) { "MIDI track extends past end of file" }

            var tick = 0L
            var runningStatus = -1
            val openNotes = mutableMapOf<Int, ArrayDeque<OpenNote>>()

            while (c.position < trackEnd) {
                tick += c.readVarLen().toLong()
                totalTicks = maxOf(totalTicks, tick)

                var status = c.peekU8()
                if (status >= 0x80) {
                    status = c.readU8()
                    if (status < 0xF0) runningStatus = status
                } else {
                    require(runningStatus >= 0) { "Running status used before a channel status byte" }
                    status = runningStatus
                }

                when {
                    status == 0xFF -> {
                        val type = c.readU8()
                        val len = c.readVarLen()
                        when (type) {
                            0x2F -> {
                                c.skip(len)
                                break
                            }
                            0x51 -> {
                                if (len == 3) {
                                    val us = (c.readU8() shl 16) or (c.readU8() shl 8) or c.readU8()
                                    if (us > 0) tempos += TempoEvent(tick, us)
                                } else {
                                    c.skip(len)
                                }
                            }
                            0x58 -> {
                                if (len >= 2) {
                                    val numerator = c.readU8()
                                    val denominatorPow = c.readU8().coerceIn(0, 7)
                                    val denominator = 1 shl denominatorPow
                                    if (numerator > 0) {
                                        signatures += TimeSignatureEvent(tick, numerator, denominator)
                                    }
                                    c.skip(len - 2)
                                } else {
                                    c.skip(len)
                                }
                            }
                            else -> c.skip(len)
                        }
                    }

                    status == 0xF0 || status == 0xF7 -> {
                        val len = c.readVarLen()
                        c.skip(len)
                    }

                    else -> {
                        val command = status and 0xF0
                        val channel = status and 0x0F
                        when (command) {
                            0x80 -> {
                                val pitch = c.readU8()
                                c.readU8() // release velocity
                                closeNote(openNotes, notes, trackIndex, channel, pitch, tick)
                            }
                            0x90 -> {
                                val pitch = c.readU8()
                                val velocity = c.readU8()
                                if (velocity == 0) {
                                    closeNote(openNotes, notes, trackIndex, channel, pitch, tick)
                                } else {
                                    val key = (channel shl 8) or pitch
                                    openNotes.getOrPut(key) { ArrayDeque() }
                                        .addLast(OpenNote(tick, velocity))
                                }
                            }
                            0xA0, 0xB0, 0xE0 -> {
                                c.readU8(); c.readU8()
                            }
                            0xC0, 0xD0 -> c.readU8()
                            else -> error("Unsupported MIDI status 0x${status.toString(16)}")
                        }
                    }
                }
            }

            if (c.position < trackEnd) c.skip(trackEnd - c.position)
            require(c.position == trackEnd) { "MIDI parser crossed track boundary" }
        }

        val sortedTempos = tempos
            .distinctBy { it.tick to it.microsecondsPerQuarter }
            .sortedBy { it.tick }
            .toMutableList()
        if (sortedTempos.none { it.tick == 0L }) {
            sortedTempos.add(0, TempoEvent(0, 500_000)) // MIDI default: 120 BPM
        }

        val sortedSignatures = signatures
            .distinctBy { Triple(it.tick, it.numerator, it.denominator) }
            .sortedBy { it.tick }
            .toMutableList()
        if (sortedSignatures.none { it.tick == 0L }) {
            sortedSignatures.add(0, TimeSignatureEvent(0, 4, 4))
        }

        val sortedNotes = notes.sortedWith(compareBy<MidiNote> { it.startTick }.thenBy { it.pitch })
        totalTicks = maxOf(totalTicks, sortedNotes.maxOfOrNull { it.endTick } ?: 0L)

        return MidiScore(
            format = format,
            ppq = ppq,
            notes = sortedNotes,
            tempoMap = sortedTempos,
            timeSignatures = sortedSignatures,
            totalTicks = totalTicks,
        )
    }

    private fun closeNote(
        openNotes: MutableMap<Int, ArrayDeque<OpenNote>>,
        notes: MutableList<MidiNote>,
        track: Int,
        channel: Int,
        pitch: Int,
        tick: Long,
    ) {
        val key = (channel shl 8) or pitch
        val queue = openNotes[key] ?: return
        val open = queue.pollFirst() ?: return
        if (queue.isEmpty()) openNotes.remove(key)
        notes += MidiNote(
            track = track,
            channel = channel,
            pitch = pitch,
            velocity = open.velocity,
            startTick = open.tick,
            endTick = maxOf(tick, open.tick + 1),
        )
    }

    private data class OpenNote(val tick: Long, val velocity: Int)

    private class Cursor(private val data: ByteArray) {
        var position: Int = 0
            private set

        fun readU8(): Int {
            require(position < data.size) { "Unexpected end of MIDI file" }
            return data[position++].toInt() and 0xff
        }

        fun peekU8(): Int {
            require(position < data.size) { "Unexpected end of MIDI file" }
            return data[position].toInt() and 0xff
        }

        fun readU16(): Int = (readU8() shl 8) or readU8()

        fun readU32(): Long =
            (readU8().toLong() shl 24) or
                (readU8().toLong() shl 16) or
                (readU8().toLong() shl 8) or
                readU8().toLong()

        fun readVarLen(): Int {
            var value = 0
            repeat(4) {
                val b = readU8()
                value = (value shl 7) or (b and 0x7f)
                if ((b and 0x80) == 0) return value
            }
            error("Invalid MIDI variable-length quantity")
        }

        fun skip(count: Int) {
            require(count >= 0 && position + count <= data.size) { "Invalid MIDI skip" }
            position += count
        }

        fun expectAscii(expected: String) {
            for (ch in expected) {
                require(readU8() == ch.code) { "Expected MIDI chunk '$expected'" }
            }
        }
    }
}
