package de.kleinabersmart.earloft.core.playback

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
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.model.AppUris
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Serves album covers as `content://<application ID>.covers/album/<id>` (books and episodes: `…/book/<id>`, podcast subscriptions:
 * `…/feed/<id>`). Android Auto loads the covers of the browse lists
 * itself via such a URI; it cannot read `earloft://cover/…`. The images come from the same pipeline as in the
 * app ([ArtworkRepository]), so also from servers requiring sign-in.
 *
 * The provider is not exported and read-only: only the app itself and controllers that the session grants the right
 * on connect may read ([LibrarySessionCallback.onConnect], prefix grant on [rootUri]). Otherwise any app could
 * query the library via the covers and thereby trigger authenticated requests to the user's own server.
 */
class CoverProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Entry {
        fun artworkRepository(): ArtworkRepository
        fun bookCovers(): BookCovers
    }

    override fun onCreate(): Boolean {
        // The provider starts with the process, before service and app: from here on the addresses exist without a Context ([queueCoverUri]).
        context?.let { authorityName = authority(it) }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only")
        val albumId = albumId(uri)
        val bookId = bookId(uri)
        val feedId = feedId(uri)
        if (albumId == null && bookId == null && feedId == null) throw FileNotFoundException(uri.toString())
        val context = context ?: throw FileNotFoundException(uri.toString())
        val entry = EntryPointAccessors.fromApplication(context, Entry::class.java)
        // The pipe helper threads may block; the binder thread returns immediately.
        return openPipeHelper(uri, MIME_TYPE, null, null) { output, _, _, _, _ ->
            val bitmap = runBlocking {
                when {
                    albumId != null -> entry.artworkRepository().albumCover(albumId, SIZE_PX)
                    bookId != null -> entry.bookCovers().cover(bookId)
                    else -> entry.bookCovers().feedThumbnail(feedId!!, SIZE_PX)
                }
            }
            FileOutputStream(output.fileDescriptor).use { stream ->
                bitmap?.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
        }
    }

    override fun getType(uri: Uri): String? = MIME_TYPE.takeIf { albumId(uri) != null || bookId(uri) != null || feedId(uri) != null }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val MIME_TYPE = "image/jpeg"
        private const val ALBUM_SEGMENT = "album"
        private const val BOOK_SEGMENT = "book"
        private const val FEED_SEGMENT = "feed"
        private const val SIZE_PX = 512
        private const val JPEG_QUALITY = 90

        fun authority(context: Context): String = "${context.packageName}.covers"

        @Volatile
        private var authorityName: String? = null

        /**
         * Cover of a queue entry: the [CoverProvider] address that Android Auto and the system UI can
         * load; without a running provider (unit tests) `earloft://cover/…`. [ArtworkBitmapLoader] understands both.
         */
        fun queueCoverUri(albumId: Long): Uri =
            authorityName?.let { Uri.Builder().scheme("content").authority(it).appendPath(ALBUM_SEGMENT).appendPath(albumId.toString()).build() }
                ?: Uri.parse(AppUris.albumCover(albumId))

        fun queueBookCoverUri(bookId: Long): Uri =
            authorityName?.let { Uri.Builder().scheme("content").authority(it).appendPath(BOOK_SEGMENT).appendPath(bookId.toString()).build() }
                ?: Uri.parse(AppUris.bookCover(bookId))

        /** Root of all cover URIs, for a grant with `FLAG_GRANT_PREFIX_URI_PERMISSION`. */
        fun rootUri(context: Context): Uri = Uri.Builder().scheme("content").authority(authority(context)).build()

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

        /** Image of a podcast subscription; the ID is encoded since it may contain slashes (subscriptions from the device). */
        fun feedCoverUri(context: Context, feedId: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(FEED_SEGMENT)
                .appendPath(feedId)
                .build()

        internal fun albumId(uri: Uri): Long? = id(uri, ALBUM_SEGMENT)

        internal fun bookId(uri: Uri): Long? = id(uri, BOOK_SEGMENT)

        internal fun feedId(uri: Uri): String? = segment(uri, FEED_SEGMENT)?.takeIf { it.isNotEmpty() }

        private fun id(uri: Uri, segment: String): Long? = segment(uri, segment)?.toLongOrNull()

        private fun segment(uri: Uri, segment: String): String? {
            val segments = uri.pathSegments
            return segments.takeIf { it.size == 2 && it[0] == segment }?.get(1)
        }
    }
}
