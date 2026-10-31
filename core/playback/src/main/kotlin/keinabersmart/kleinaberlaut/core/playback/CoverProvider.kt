package keinabersmart.kleinaberlaut.core.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Liefert Album-Cover als `content://<Anwendungs-ID>.covers/album/<id>` (Bücher und Folgen: `…/book/<id>`). Android Auto lädt die Cover der Browse-Listen
 * selbst über eine solche URI; `kal://cover/…` kann es nicht lesen. Die Bilder kommen aus derselben Pipeline wie in der
 * App ([ArtworkRepository]), also auch von Servern mit Anmeldung.
 *
 * Der Provider ist exportiert und nur lesend – er gibt ausschließlich Cover heraus.
 */
class CoverProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Entry {
        fun artworkRepository(): ArtworkRepository
        fun bookCovers(): BookCovers
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Nur lesbar")
        val albumId = albumId(uri)
        val bookId = bookId(uri)
        if (albumId == null && bookId == null) throw FileNotFoundException(uri.toString())
        val context = context ?: throw FileNotFoundException(uri.toString())
        val entry = EntryPointAccessors.fromApplication(context, Entry::class.java)
        // Die Pipe-Helfer-Threads dürfen blockieren; der Binder-Thread kehrt sofort zurück.
        return openPipeHelper(uri, MIME_TYPE, null, null) { output, _, _, _, _ ->
            val bitmap = runBlocking {
                if (albumId != null) entry.artworkRepository().albumCover(albumId, SIZE_PX) else entry.bookCovers().cover(bookId!!)
            }
            FileOutputStream(output.fileDescriptor).use { stream ->
                bitmap?.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
        }
    }

    override fun getType(uri: Uri): String? = MIME_TYPE.takeIf { albumId(uri) != null || bookId(uri) != null }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val MIME_TYPE = "image/jpeg"
        private const val ALBUM_SEGMENT = "album"
        private const val BOOK_SEGMENT = "book"
        private const val SIZE_PX = 512
        private const val JPEG_QUALITY = 90

        fun authority(context: Context): String = "${context.packageName}.covers"

        fun albumCoverUri(context: Context, albumId: Long): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(ALBUM_SEGMENT)
                .appendPath(albumId.toString())
                .build()

        fun bookCoverUri(context: Context, bookId: Long): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(BOOK_SEGMENT)
                .appendPath(bookId.toString())
                .build()

        internal fun albumId(uri: Uri): Long? = id(uri, ALBUM_SEGMENT)

        internal fun bookId(uri: Uri): Long? = id(uri, BOOK_SEGMENT)

        private fun id(uri: Uri, segment: String): Long? {
            val segments = uri.pathSegments
            return segments.takeIf { it.size == 2 && it[0] == segment }?.get(1)?.toLongOrNull()
        }
    }
}
