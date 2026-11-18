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

    fun createFile(parent: Uri, name: String, mime: String = "application/octet-stream"): OutputStream {
        require(PathSafety.isValidFileName(name)) { "Invalid file name" }
        children(parent).firstOrNull { it.first == name }?.let { DocumentsContract.deleteDocument(resolver, it.second) }
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("Cannot create $name")
        return resolver.openOutputStream(doc, "wt") ?: throw IOException("Cannot write $name")
    }
}
