package com.einhander.temposcore

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test fun partialWritesCountOnlyCompleteFramesAndIgnoreErrors() {
        val run = PlaybackDiagnostics.start(1, 1)
        PlaybackDiagnostics.written(run, 2, 2, 10)
        PlaybackDiagnostics.written(run, 2, 2, 6)
        PlaybackDiagnostics.written(run, 2, 2, -3)
        val snapshot = PlaybackDiagnostics.snapshot(run)
        assertEquals(4L, snapshot.writtenFrames)
        assertEquals(1L, snapshot.errorCount)
    }

    @Test fun partialWritesCarryIncompleteInterleavedFrameBytes() {
        val run = PlaybackDiagnostics.start(13, 23)
        PlaybackDiagnostics.written(run, 2, 2, 2)
        assertEquals(0L, PlaybackDiagnostics.snapshot(run).writtenFrames)
        PlaybackDiagnostics.written(run, 2, 2, 2)
        assertEquals(1L, PlaybackDiagnostics.snapshot(run).writtenFrames)
    }

    @Test fun playbackHeadUnsignedWrapAndResetAreEpochBounded() {
        val first = PlaybackDiagnostics.start(2, 4)
        PlaybackDiagnostics.fallbackHead(first, 0x7fff_fff0)
        PlaybackDiagnostics.fallbackHead(first, 0xffff_fff0.toInt())
        PlaybackDiagnostics.fallbackHead(first, 0x0000_0010)
        assertEquals(0x1_0000_0010L, PlaybackDiagnostics.snapshot(first).headFrames)
        val second = PlaybackDiagnostics.start(3, 5)
        PlaybackDiagnostics.fallbackHead(second, 12)
        PlaybackDiagnostics.fallbackHead(first, 1000) // Retired run cannot mutate current run.
        assertEquals(12L, PlaybackDiagnostics.snapshot(second).headFrames)
        assertEquals(5L, PlaybackDiagnostics.snapshot(second).epoch)
    }

    @Test fun timestampSupportedAndUnsupportedFallbackAreExplicit() {
        val supported = PlaybackDiagnostics.start(4, 6)
        PlaybackDiagnostics.timestamp(supported, 100, 123456789L)
        assertEquals("timestamp", PlaybackDiagnostics.snapshot(supported).headSource)
        val unsupported = PlaybackDiagnostics.start(5, 7)
        assertEquals("unsupported", PlaybackDiagnostics.snapshot(unsupported).headSource)
        PlaybackDiagnostics.fallbackHead(unsupported, 10)
        assertEquals("playback_head_fallback", PlaybackDiagnostics.snapshot(unsupported).headSource)
        assertEquals(null, PlaybackDiagnostics.snapshot(unsupported).timestampMonotonicNs)
    }

    @Test fun pauseAndEosDoNotInventFrameGrowth() {
        val run = PlaybackDiagnostics.start(6, 8)
        PlaybackDiagnostics.decoded(run, 64, 9000)
        PlaybackDiagnostics.paused(run, true)
        PlaybackDiagnostics.eos(run)
        val snapshot = PlaybackDiagnostics.snapshot(run)
        assertEquals(64L, snapshot.decodedFrames)
        assertEquals(0L, snapshot.writtenFrames)
        assertEquals(0L, snapshot.pushSubmittedFrames)
        assertTrue(snapshot.paused)
        assertTrue(snapshot.eos)
    }

    @Test fun csvLabelsPlaybackHeadAsEstimateAndKeepsDecoderPtsSeparate() {
        val run = PlaybackDiagnostics.start(7, 9)
        PlaybackDiagnostics.setSampleRate(run, 48000)
        PlaybackDiagnostics.decoded(run, 10, 1234)
        PlaybackDiagnostics.fallbackHead(run, 8)
        val csv = PlaybackDiagnostics.csv(PlaybackDiagnostics.snapshot(run))
        assertTrue(csv.contains("sample_rate_hz,format_epoch,decoded_frames"))
        assertTrue(csv.contains("decoder_pts_us,heard_frame_estimate,head_source"))
        assertTrue(csv.contains("playback_head_fallback"))
        assertFalse(csv.contains("score_position"))
    }

    @Test fun formatsRecordSampleRateEpochBoundaries() {
        val run = PlaybackDiagnostics.start(8, 10)
        PlaybackDiagnostics.setSampleRate(run, 44100)
        PlaybackDiagnostics.decoded(run, 100, 1000)
        PlaybackDiagnostics.setSampleRate(run, 48000)
        val snapshot = PlaybackDiagnostics.snapshot(run)
        assertEquals(48000, snapshot.sampleRate)
        assertEquals(2, snapshot.formatEpoch)
        assertEquals(2, snapshot.formats.size)
        assertEquals(100L, snapshot.formats[1].decodedFramesAtChange)
    }

    @Test fun newRunOwnsOnlyItsDisplayRowsAndOldSessionExportsItsRetiredSnapshot() {
        val old = PlaybackDiagnostics.start(11, 21)
        PlaybackDiagnostics.recordDisplay(old, 100L, 1.0, 1.1)
        val oldSnapshot = PlaybackDiagnostics.snapshot(old)
        val newer = PlaybackDiagnostics.start(12, 22)
        PlaybackDiagnostics.recordDisplay(old, 200L, 9.0, 9.1) // Stale token must not append.
        PlaybackDiagnostics.recordDisplay(newer, 300L, 2.0, 2.1)
        val newCsv = PlaybackDiagnostics.csv(PlaybackDiagnostics.snapshot(newer))
        assertTrue(newCsv.contains("2.000000000"))
        assertFalse(newCsv.contains("1.000000000"))
        assertFalse(newCsv.contains("9.000000000"))
        val root = Files.createTempDirectory("playback-retired-test").toFile()
        try {
            val exported = PlaybackDiagnostics.export(root, oldSnapshot)
            val oldCsv = exported.readText()
            assertTrue(exported.name == "playback-11-epoch-21.csv")
            assertTrue(oldCsv.contains("1.000000000"))
            assertFalse(oldCsv.contains("9.000000000"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun exportWritesSessionNamedCsvAndBoundsRetention() {
        val root = Files.createTempDirectory("playback-diag-test").toFile()
        try {
            for (index in 1L..22L) {
                val session = PlaybackDiagnostics.start(index, index + 100)
                PlaybackDiagnostics.setSampleRate(session, 48000)
                val written = PlaybackDiagnostics.export(root, PlaybackDiagnostics.snapshot(session))
                assertTrue(written.name == "playback-$index-epoch-${index + 100}.csv")
                assertTrue(written.length() <= 256 * 1024)
            }
            assertEquals(20, root.listFiles { file -> file.name.endsWith(".csv") }!!.size)
        } finally {
            root.deleteRecursively()
        }
    }
}
