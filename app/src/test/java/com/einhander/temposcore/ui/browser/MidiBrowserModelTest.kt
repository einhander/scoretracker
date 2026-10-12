package com.einhander.temposcore.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MidiBrowserModelTest {
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
