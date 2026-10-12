package com.einhander.temposcore.ui.browser

import java.util.Locale

data class BrowserDocument(val id: String, val name: String, val directory: Boolean)

private val midiMimeTypes = setOf("audio/midi", "audio/x-midi", "audio/mid", "audio/sp-midi", "application/x-midi")
private val mp3MimeTypes = setOf("audio/mpeg", "audio/mp3")

enum class BrowserMode {
    MIDI, MP3;

    fun accepts(name: String, mime: String?): Boolean = when (this) {
        MIDI -> isSupportedMidi(name, mime)
        MP3 -> name.lowercase(Locale.ROOT).endsWith(".mp3") || mime?.lowercase(Locale.ROOT) in mp3MimeTypes
    }
}

/** A persisted tree grant is shared when both pickers refer to the same URI. */
fun shouldReleaseTreePermission(previous: String?, replacement: String, otherPickerTree: String?): Boolean =
    previous != null && previous != replacement && previous != otherPickerTree

fun isSupportedMidi(name: String, mime: String?): Boolean {
    val normalized = name.lowercase(Locale.ROOT)
    return normalized.endsWith(".mid") || normalized.endsWith(".midi") ||
        mime?.lowercase(Locale.ROOT) in midiMimeTypes
}

fun sortedBrowserDocuments(documents: List<BrowserDocument>): List<BrowserDocument> =
    documents.distinctBy { it.id }.sortedWith(compareBy<BrowserDocument> { !it.directory }
        .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.id })

/** Document IDs are opaque, not filesystem paths. Only listed children can be entered. */
class BrowserNavigation(root: BrowserDocument) {
    private val folders = arrayListOf(root)
    val current: BrowserDocument get() = folders.last()
    val canGoUp: Boolean get() = folders.size > 1
    val path: List<BrowserDocument> get() = folders.toList()

    fun enter(document: BrowserDocument, listed: List<BrowserDocument>): Boolean {
        if (!document.directory || document !in listed || folders.any { it.id == document.id }) return false
        folders.add(document)
        return true
    }

    fun up(): Boolean {
        if (!canGoUp) return false
        folders.removeAt(folders.lastIndex)
        return true
    }
}
