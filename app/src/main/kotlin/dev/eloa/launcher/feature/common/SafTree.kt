package dev.eloa.launcher.feature.common

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.eloa.launcher.core.common.io.PathSafety
import java.io.IOException
import java.io.OutputStream

/**
 * Minimal writer for a folder the user granted through ACTION_OPEN_DOCUMENT_TREE.
 * Only creates children of that tree; never touches other locations.
 */
class SafTree(private val resolver: ContentResolver, private val treeUri: Uri) {
    private val rootDoc: Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    fun root(): Uri = rootDoc

    fun children(parent: Uri): List<Pair<String, Uri>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        val out = ArrayList<Pair<String, Uri>>()
        resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out += c.getString(1) to DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
        }
        return out
    }

    fun dir(parent: Uri, name: String): Uri {
        require(PathSafety.isValidFileName(name)) { "Invalid folder name" }
        children(parent).firstOrNull { it.first == name }?.let { return it.second }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create folder $name")
    }

    fun dirPath(path: String): Uri = path.split('/').filter { it.isNotEmpty() }.fold(rootDoc) { acc, seg -> dir(acc, seg) }

    data class Doc(val name: String, val uri: Uri, val isDir: Boolean, val size: Long)

    fun list(parent: Uri): List<Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
        val out = ArrayList<Doc>()
        resolver.query(childrenUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Doc(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                    c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0 else c.getLong(3))
            }
        }
        return out
    }

    /** All files below [dir] with paths relative to it ('/'-separated). Depth-limited. */
    fun walkFiles(dir: Uri, prefix: String = "", depth: Int = 0): Sequence<Pair<String, Doc>> = sequence {
        if (depth > 12) return@sequence
        for (d in list(dir)) {
            val path = if (prefix.isEmpty()) d.name else "$prefix/${d.name}"
            if (d.isDir) yieldAll(walkFiles(d.uri, path, depth + 1)) else yield(path to d)
        }
    }

    fun find(parent: Uri, name: String): Doc? = list(parent).firstOrNull { it.name == name }

    fun delete(doc: Uri): Boolean = DocumentsContract.deleteDocument(resolver, doc)

    fun open(doc: Uri) = resolver.openInputStream(doc) ?: throw IOException("Cannot read document")

    fun createFile(parent: Uri, name: String, mime: String = "application/octet-stream"): OutputStream {
        require(PathSafety.isValidFileName(name)) { "Invalid file name" }
        children(parent).firstOrNull { it.first == name }?.let { DocumentsContract.deleteDocument(resolver, it.second) }
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("Cannot create $name")
        return resolver.openOutputStream(doc, "wt") ?: throw IOException("Cannot write $name")
    }
}
