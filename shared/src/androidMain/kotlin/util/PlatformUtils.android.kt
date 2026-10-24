package util

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import native.PayloadExtractNative

actual fun initialOutputDir(): String {
    val saved = prefGet("outputDir") ?: return ""
    val treeUri = Uri.parse(saved)
    if (treeUri.scheme != ContentResolver.SCHEME_CONTENT || !DocumentsContract.isTreeUri(treeUri)) return ""
    val resolver = AndroidAppContext.get()?.contentResolver ?: return ""
    return if (resolver.persistedUriPermissions.any {
        it.uri == treeUri && it.isReadPermission && it.isWritePermission
    }) saved else ""
}

private val outputDocumentLock = Any()

actual fun extractPartitionToOutput(
    handle: Long,
    outputDir: String,
    outputSubdir: String,
    partitionName: String,
    threads: Int,
    verify: Boolean,
    token: Long,
) {
    val treeUri = Uri.parse(outputDir)

    val context = AndroidAppContext.get() ?: error("Android application context is not initialized")
    val resolver = context.contentResolver
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val outputUri = synchronized(outputDocumentLock) {
        val directoryUri = resolver.findOrCreateOutputDocument(
            treeUri,
            rootUri,
            outputSubdir,
            DocumentsContract.Document.MIME_TYPE_DIR,
        )
        resolver.findOrCreateOutputDocument(
            treeUri,
            directoryUri,
            "${partitionName}.img",
            "application/octet-stream",
        )
    }
    val descriptor = resolver.openFileDescriptor(outputUri, "rwt")
        ?: error("Cannot open output document: $outputUri")
    descriptor.use {
        PayloadExtractNative.extractPartitionToFd(
            handle,
            it.fd,
            context.cacheDir.absolutePath,
            partitionName,
            threads,
            verify,
            token,
        )
    }
}

private fun ContentResolver.findOrCreateOutputDocument(
    treeUri: Uri,
    parentUri: Uri,
    displayName: String,
    mimeType: String,
): Uri {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getDocumentId(parentUri),
    )
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    val children = query(childrenUri, columns, null, null, null)
        ?: error("Cannot query output directory: $parentUri")
    children.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val typeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            if (cursor.getString(nameColumn) != displayName) continue
            val existingIsDirectory = cursor.getString(typeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
            val requestedIsDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            check(existingIsDirectory == requestedIsDirectory) {
                "Output name conflicts with an existing ${if (existingIsDirectory) "directory" else "file"}: $displayName"
            }
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
        }
    }
    return DocumentsContract.createDocument(this, parentUri, mimeType, displayName)
        ?: error("Cannot create output document: $displayName")
}
