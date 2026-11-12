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
 * Lädt `earloft://cover/...` und die [CoverProvider]-Adressen über dieselbe Artwork-Pipeline wie die App, damit Benachrichtigung,
 * Sperrbildschirm und Uhr die Cover jeder Quelle bekommen. Alles andere übernimmt Media3.
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
        // `earloft://cover/…` und die gleichen Cover als CoverProvider-Adresse (so tragen sie die Einträge der Warteschlange).
        val bookId = AppUris.parseBookCoverId(uri.toString()) ?: CoverProvider.bookId(uri)
        if (bookId != null) {
            return scope.future(Dispatchers.IO) { bookCovers.cover(bookId) ?: throw IOException("Kein Cover für Buch $bookId") }
        }
        val albumId = AppUris.parseAlbumCoverId(uri.toString()) ?: CoverProvider.albumId(uri) ?: return fallback.loadBitmap(uri)
        return scope.future(Dispatchers.IO) {
            artworkRepository.albumCover(albumId, ARTWORK_SIZE_PX) ?: throw IOException("Kein Cover für Album $albumId")
        }
    }

    private companion object {
        const val ARTWORK_SIZE_PX = 512
    }
}
