package com.einhander.temposcore.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MidiBrowserModelTest {
    @Test fun mp3ModeAcceptsMp3AndMpegMimeButNotUnrelatedAudioOrMidi() {
        for (name in listOf("song.mp3", "SONG.MP3", "song.Mp3")) {
            assertTrue(BrowserMode.MP3.accepts(name, "application/octet-stream"))
            assertFalse(BrowserMode.MIDI.accepts(name, "application/octet-stream"))
        }
        for (mime in listOf("audio/mpeg", "audio/mp3", "AUDIO/MPEG")) {
            assertTrue(BrowserMode.MP3.accepts("no extension", mime))
        }
        for ((name, mime) in listOf("song.wav" to "audio/wav", "song.ogg" to "audio/ogg",
            "song.aac" to "audio/aac", "score.mid" to "audio/midi", "song.mp3.bak" to "application/octet-stream",
            "anything" to "audio/*", "directory" to "vnd.android.document/directory")) {
            assertFalse(BrowserMode.MP3.accepts(name, mime))
        }
        assertFalse(BrowserMode.MP3.accepts("", null))
        assertTrue(BrowserMode.MIDI.accepts("score.MIDI", null))
        assertTrue(BrowserMode.MIDI.accepts("no extension", "audio/x-midi"))
    }

    @Test fun permissionReleaseRetainsTreesStillUsedByEitherPicker() {
        val shared = "content://provider/tree/shared"
        val replacement = "content://provider/tree/new"
        assertFalse(shouldReleaseTreePermission(null, replacement, null))
        assertFalse(shouldReleaseTreePermission(shared, shared, null))
        assertFalse(shouldReleaseTreePermission(shared, replacement, shared))
        assertTrue(shouldReleaseTreePermission(shared, replacement, null))
        assertTrue(shouldReleaseTreePermission(shared, replacement, "content://provider/tree/other"))

        // MIDI leaves a shared folder: keep it for MP3. MP3 then leaves: release it.
        var midiTree = shared
        var mp3Tree = shared
        assertFalse(shouldReleaseTreePermission(midiTree, replacement, mp3Tree))
        midiTree = replacement
        assertTrue(shouldReleaseTreePermission(mp3Tree, replacement, midiTree))
        mp3Tree = replacement
        assertFalse(shouldReleaseTreePermission(midiTree, shared, mp3Tree))
        midiTree = shared
        assertTrue(shouldReleaseTreePermission(mp3Tree, shared, midiTree))
    }

    @Test fun independentNavigationDoesNotMoveTheOtherPickerEvenWithSharedRoot() {
        val root = BrowserDocument("shared-root", "Root", true)
        val midi = BrowserNavigation(root)
        val mp3 = BrowserNavigation(root)
        val scores = BrowserDocument("scores", "Scores", true)
        val recordings = BrowserDocument("recordings", "Recordings", true)
        assertTrue(midi.enter(scores, listOf(scores, recordings)))
        assertEquals(root, mp3.current)
        assertTrue(mp3.enter(recordings, listOf(scores, recordings)))
        assertEquals(scores, midi.current)
        assertTrue(midi.up())
        assertEquals(recordings, mp3.current)
        assertFalse(midi.canGoUp)
        assertTrue(mp3.up())
        assertFalse(mp3.up())
    }

    @Test fun filtersMidiExtensionsAndMimeWithoutAcceptingGenericBinaryFiles() {
        for (name in listOf("score.mid", "Score.MIDI", "piece.MiD")) assertTrue(isSupportedMidi(name, null))
        for (mime in listOf("audio/midi", "audio/x-midi", "audio/mid", "audio/sp-midi", "application/x-midi", "AUDIO/MIDI")) {
            assertTrue(isSupportedMidi("no extension", mime))
        }
        for (name in listOf("score.mid.bak", "song.mp3", "image.png", "binary", "")) {
            assertFalse(isSupportedMidi(name, "application/octet-stream"))
        }
        assertFalse(isSupportedMidi("directory", "vnd.android.document/directory"))
    }

    @Test fun extensionFilteringDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(isSupportedMidi("PIECE.MIDI", null))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun foldersSortFirstAndDuplicateIdsDoNotCreateDuplicateRows() {
        val midi = BrowserDocument("m", "Alpha.mid", false)
        val folders = listOf(BrowserDocument("z", "Z folder", true), BrowserDocument("a", "a folder", true))
        assertEquals(listOf(folders[1], folders[0], midi), sortedBrowserDocuments(listOf(midi) + folders + midi))
    }

    @Test fun navigationCannotLeaveRootOrEnterFilesUnlistedFoldersOrCycles() {
        val root = BrowserDocument("opaque:root/with/slashes", "Root", true)
        val child = BrowserDocument("not/a/filesystem/path", "Folder", true)
        val file = BrowserDocument("file", "score.mid", false)
        val nav = BrowserNavigation(root)
        assertFalse(nav.canGoUp)
        assertFalse(nav.up())
        assertFalse(nav.enter(file, listOf(file)))
        assertFalse(nav.enter(child, emptyList()))
        assertTrue(nav.enter(child, listOf(child)))
        assertEquals(child, nav.current)
        assertTrue(nav.canGoUp)
        assertFalse(nav.enter(root, listOf(root)))
        assertFalse(nav.enter(child, listOf(child)))
        assertEquals(listOf(root, child), nav.path)
        assertTrue(nav.up())
        assertEquals(root, nav.current)
        assertFalse(nav.up())
    }
}
