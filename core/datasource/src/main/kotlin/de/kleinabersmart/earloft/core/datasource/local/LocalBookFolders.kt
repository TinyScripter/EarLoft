package de.kleinabersmart.earloft.core.datasource.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Access grant for the "Audiobooks" folder via the Storage Access Framework (`books_improvements.md` M7). With the audio permission
 * the app only sees audio files; it can read the cover, `desc.txt`, `reader.txt` and `metadata.opf` next to them only with this
 * grant. It is optional: without it, tags and folder names count. Only applies to folders on the device's storage
 * (`ExternalStorageProvider`), whose ID follows from the path MediaStore reports.
 */
@Singleton
class LocalBookFolders @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    /** The folder picker starts here: "Audiobooks" in internal storage. */
    val initialUri: Uri get() = DocumentsContract.buildDocumentUri(AUTHORITY, "$PRIMARY:${LocalBookLayout.ROOT}")

    /** There is a grant. */
    fun isShared(): Boolean = trees().isNotEmpty()

    /**
     * Takes over the chosen grant if it is the "Audiobooks" folder or contains it (root of a storage volume).
     * Otherwise `false`, and the app keeps nothing.
     */
    fun share(tree: Uri): Boolean {
        if (tree.authority != AUTHORITY || !DocumentsContract.isTreeUri(tree)) return false
        val path = DocumentsContract.getTreeDocumentId(tree).substringAfter(':')
        if (path.isNotEmpty() && !path.substringAfterLast('/').equals(LocalBookLayout.ROOT, ignoreCase = true)) return false
        return try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Grant for $tree not taken", e)
            false
        }
    }

    /** Releases all grants. */
    fun unshare() {
        trees().forEach { resolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    internal fun trees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.uri.authority == AUTHORITY && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    /**
     * The files of the folder with the path [folderPath] (as MediaStore reports it); empty if no grant in [trees] covers it.
     * `null` if it can't be read right now: that is not the same as "no companion files".
     */
    internal fun list(folderPath: String, trees: List<Uri>): List<SideFile>? {
        val documentId = documentId(folderPath) ?: return emptyList()
        val tree = trees.firstOrNull { covers(DocumentsContract.getTreeDocumentId(it), documentId) } ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)
        return try {
            val cursor = resolver.query(children, columns, null, null, null) ?: return null
            cursor.use {
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(4) == Document.MIME_TYPE_DIR) continue
                        val id = cursor.getString(0) ?: continue
                        add(
                            SideFile(
                                name = cursor.getString(1) ?: continue,
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                                sizeBytes = if (cursor.isNull(2)) 0 else cursor.getLong(2),
                                modifiedMs = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                            ),
                        )
                    }
                }
            }
        } catch (e: RuntimeException) {
            // Grant revoked, folder gone (SecurityException, IllegalArgumentException, …).
            Log.w(TAG, "Folder $folderPath unreadable", e)
            null
        }
    }

    /** Content of a file from [list], at most [maxBytes] in size; `null` if it is larger or not readable. */
    fun read(uri: String, maxBytes: Long): ByteArray? = try {
        resolver.openInputStream(Uri.parse(uri))?.use { stream ->
            val bytes = stream.readNBytesCompat(maxBytes + 1)
            bytes.takeIf { it.size <= maxBytes }
        }
    } catch (e: IOException) {
        Log.w(TAG, "$uri unreadable", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "$uri unreadable", e)
        null
    }

    /** `readNBytes` only exists from Android 13 on. */
    private fun InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), limit - out.size()).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    internal companion object {
        const val TAG = "LocalBookFolders"
        const val AUTHORITY = "com.android.externalstorage.documents"
        private const val PRIMARY = "primary"
        private val EMULATED = Regex("""^/storage/emulated/\d+/(.*)$""")
        private val VOLUME = Regex("""^/storage/([^/]+)/(.*)$""")
        private val SDCARD = Regex("""^/sdcard/(.*)$""")

        /** ID of the folder [path] at `ExternalStorageProvider` ("primary:Audiobooks/A"); `null` for other locations. */
        fun documentId(path: String): String? {
            val clean = path.trimEnd('/')
            EMULATED.matchEntire(clean)?.let { return "$PRIMARY:${it.groupValues[1]}" }
            SDCARD.matchEntire(clean)?.let { return "$PRIMARY:${it.groupValues[1]}" }
            VOLUME.matchEntire(clean)?.let { return "${it.groupValues[1]}:${it.groupValues[2]}" }
            return null
        }

        /** The grant with the ID [treeId] covers the folder [documentId]. */
        fun covers(treeId: String, documentId: String): Boolean {
            val treeVolume = treeId.substringBefore(':')
            val treePath = treeId.substringAfter(':', "")
            if (!documentId.startsWith("$treeVolume:")) return false
            val path = documentId.substringAfter(':')
            return treePath.isEmpty() || path.equals(treePath, ignoreCase = true) || path.startsWith("$treePath/", ignoreCase = true)
        }
    }
}
