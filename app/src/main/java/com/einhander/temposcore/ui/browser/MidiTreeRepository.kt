package com.einhander.temposcore.ui.browser

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract

data class BrowserListing(val documents: List<BrowserDocument>, val loading: Boolean, val rootName: String?)

/** Called on a worker with an application resolver; never retains a view or Activity. */
object MidiTreeRepository {
    fun list(resolver: ContentResolver, tree: Uri, folderId: String, cancellation: CancellationSignal): BrowserListing {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val rootName = resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null, cancellation)?.use {
            check(it.moveToFirst()) { "Selected directory no longer exists" }
            it.getString(0)
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS)
        val documents = ArrayList<BrowserDocument>()
        val cursor = resolver.query(children, columns, null, null, null, cancellation)
            ?: error("Provider returned no directory cursor")
        return cursor.use {
            val idColumn = it.getColumnIndexOrThrow(columns[0])
            val nameColumn = it.getColumnIndexOrThrow(columns[1])
            val mimeColumn = it.getColumnIndexOrThrow(columns[2])
            val flagsColumn = it.getColumnIndexOrThrow(columns[3])
            while (it.moveToNext()) {
                cancellation.throwIfCanceled()
                val id = it.getString(idColumn) ?: continue
                val name = it.getString(nameColumn) ?: id
                val mime = it.getString(mimeColumn)
                val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val virtual = it.getInt(flagsColumn) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
                if (directory || (!virtual && isSupportedMidi(name, mime))) {
                    documents.add(BrowserDocument(id, name, directory))
                }
            }
            BrowserListing(sortedBrowserDocuments(documents), it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false), rootName)
        }
    }
}
