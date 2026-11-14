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
 * Freigabe des Ordners „Audiobooks“ über das Storage Access Framework (`books_improvements.md` M7). Mit der Audio-Berechtigung
 * sieht die App nur Audiodateien; Cover, `desc.txt`, `reader.txt` und `metadata.opf` daneben liest sie erst mit dieser
 * Freigabe. Sie ist freiwillig: Ohne sie zählen Tags und Ordnernamen. Gilt nur für Ordner des Speichers des Geräts
 * (`ExternalStorageProvider`), deren Kennung sich aus dem Pfad ergibt, den MediaStore nennt.
 */
@Singleton
class LocalBookFolders @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    /** Hier beginnt die Ordnerauswahl: „Audiobooks“ im internen Speicher. */
    val initialUri: Uri get() = DocumentsContract.buildDocumentUri(AUTHORITY, "$PRIMARY:${LocalBookLayout.ROOT}")

    /** Es gibt eine Freigabe. */
    fun isShared(): Boolean = trees().isNotEmpty()

    /**
     * Übernimmt die gewählte Freigabe, wenn sie der Ordner „Audiobooks“ ist oder ihn enthält (Wurzel eines Speichers).
     * Sonst `false`, und die App behält nichts.
     */
    fun share(tree: Uri): Boolean {
        if (tree.authority != AUTHORITY || !DocumentsContract.isTreeUri(tree)) return false
        val path = DocumentsContract.getTreeDocumentId(tree).substringAfter(':')
        if (path.isNotEmpty() && !path.substringAfterLast('/').equals(LocalBookLayout.ROOT, ignoreCase = true)) return false
        return try {
            resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Freigabe $tree nicht übernommen", e)
            false
        }
    }

    /** Gibt alle Freigaben zurück. */
    fun unshare() {
        trees().forEach { resolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    internal fun trees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.uri.authority == AUTHORITY && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    /** Die Dateien des Ordners mit dem Pfad [folderPath] (wie MediaStore ihn nennt); leer, wenn keine Freigabe [trees] ihn umfasst. */
    internal fun list(folderPath: String, trees: List<Uri>): List<SideFile> {
        val documentId = documentId(folderPath) ?: return emptyList()
        val tree = trees.firstOrNull { covers(DocumentsContract.getTreeDocumentId(it), documentId) } ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)
        return try {
            resolver.query(children, columns, null, null, null)?.use { cursor ->
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
            }.orEmpty()
        } catch (e: RuntimeException) {
            // Freigabe zurückgenommen, Ordner verschwunden (SecurityException, IllegalArgumentException, …).
            Log.w(TAG, "Ordner $folderPath nicht lesbar", e)
            emptyList()
        }
    }

    /** Inhalt einer Datei aus [list], höchstens [maxBytes] groß; `null`, wenn sie größer oder nicht lesbar ist. */
    fun read(uri: String, maxBytes: Long): ByteArray? = try {
        resolver.openInputStream(Uri.parse(uri))?.use { stream ->
            val bytes = stream.readNBytesCompat(maxBytes + 1)
            bytes.takeIf { it.size <= maxBytes }
        }
    } catch (e: IOException) {
        Log.w(TAG, "$uri nicht lesbar", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "$uri nicht lesbar", e)
        null
    }

    /** `readNBytes` gibt es erst ab Android 13. */
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

        /** Kennung des Ordners [path] bei `ExternalStorageProvider` („primary:Audiobooks/A“); `null` für andere Orte. */
        fun documentId(path: String): String? {
            val clean = path.trimEnd('/')
            EMULATED.matchEntire(clean)?.let { return "$PRIMARY:${it.groupValues[1]}" }
            SDCARD.matchEntire(clean)?.let { return "$PRIMARY:${it.groupValues[1]}" }
            VOLUME.matchEntire(clean)?.let { return "${it.groupValues[1]}:${it.groupValues[2]}" }
            return null
        }

        /** Die Freigabe mit der Kennung [treeId] umfasst den Ordner [documentId]. */
        fun covers(treeId: String, documentId: String): Boolean {
            val treeVolume = treeId.substringBefore(':')
            val treePath = treeId.substringAfter(':', "")
            if (!documentId.startsWith("$treeVolume:")) return false
            val path = documentId.substringAfter(':')
            return treePath.isEmpty() || path.equals(treePath, ignoreCase = true) || path.startsWith("$treePath/", ignoreCase = true)
        }
    }
}
