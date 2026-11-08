package de.kleinabersmart.earloft.ui.image

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.model.AppUris
import java.io.IOException

/** Lädt `earloft://cover/book/<id>` über [BookCovers] (Server-Cover, danach aus dem Cache-Verzeichnis). */
class BookCoverFetcher(
    private val bookId: Long,
    private val options: Options,
    private val bookCovers: BookCovers,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val sizePx = maxOf(
            options.size.width.pxOrElse { DEFAULT_SIZE_PX },
            options.size.height.pxOrElse { DEFAULT_SIZE_PX },
        ).coerceAtMost(MAX_SIZE_PX)
        val bitmap = bookCovers.cover(bookId) ?: throw IOException("Kein Cover für Buch $bookId")
        val scaled = if (bitmap.width > sizePx) {
            Bitmap.createScaledBitmap(bitmap, sizePx, (bitmap.height * sizePx.toFloat() / bitmap.width).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        return ImageFetchResult(image = scaled.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val bookCovers: BookCovers) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != AppUris.SCHEME) return null
            val bookId = AppUris.parseBookCoverId(data.toString()) ?: return null
            return BookCoverFetcher(bookId, options, bookCovers)
        }
    }

    private companion object {
        const val DEFAULT_SIZE_PX = 512
        const val MAX_SIZE_PX = 800
    }
}
