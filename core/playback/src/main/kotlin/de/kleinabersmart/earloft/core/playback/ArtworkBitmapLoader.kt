package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.model.AppUris
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import java.io.IOException

/**
 * Loads `earloft://cover/...` and the [CoverProvider] addresses through the same artwork pipeline as the app, so that the notification,
 * lock screen and watch get the covers of every source. Media3 handles everything else.
 */
@OptIn(UnstableApi::class)
internal class ArtworkBitmapLoader(
    context: Context,
    private val artworkRepository: ArtworkRepository,
    private val bookCovers: BookCovers,
    private val scope: CoroutineScope,
) : BitmapLoader {

    private val fallback = DataSourceBitmapLoader.Builder(context).build()

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        // `earloft://cover/…` and the same covers as CoverProvider address (that's how the queue entries carry them).
        val bookId = AppUris.parseBookCoverId(uri.toString()) ?: CoverProvider.bookId(uri)
        if (bookId != null) {
            return scope.future(Dispatchers.IO) { bookCovers.cover(bookId) ?: throw IOException("No cover for book $bookId") }
        }
        val albumId = AppUris.parseAlbumCoverId(uri.toString()) ?: CoverProvider.albumId(uri) ?: return fallback.loadBitmap(uri)
        return scope.future(Dispatchers.IO) {
            artworkRepository.albumCover(albumId, ARTWORK_SIZE_PX) ?: throw IOException("No cover for album $albumId")
        }
    }

    private companion object {
        const val ARTWORK_SIZE_PX = 512
    }
}
