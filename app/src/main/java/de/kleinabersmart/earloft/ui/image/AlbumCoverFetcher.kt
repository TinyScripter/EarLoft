package de.kleinabersmart.earloft.ui.image

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.model.AppUris
import java.io.IOException

/**
 * Loads `earloft://cover/album/<id>` via [ArtworkRepository] – Coil doesn't need to know which
 * source a cover comes from.
 */
class AlbumCoverFetcher(
    private val albumId: Long,
    private val options: Options,
    private val artworkRepository: ArtworkRepository,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val sizePx = maxOf(
            options.size.width.pxOrElse { DEFAULT_SIZE_PX },
            options.size.height.pxOrElse { DEFAULT_SIZE_PX },
        ).coerceAtMost(MAX_SIZE_PX)
        val bitmap = artworkRepository.albumCover(albumId, sizePx)
            ?: throw IOException("Kein Cover für Album $albumId")
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val artworkRepository: ArtworkRepository) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != AppUris.SCHEME) return null
            val albumId = AppUris.parseAlbumCoverId(data.toString()) ?: return null
            return AlbumCoverFetcher(albumId, options, artworkRepository)
        }
    }

    /** Enables the memory cache for app URIs. */
    object Keyer : coil3.key.Keyer<Uri> {
        override fun key(data: Uri, options: Options): String? =
            if (data.scheme == AppUris.SCHEME) data.toString() else null
    }

    private companion object {
        const val DEFAULT_SIZE_PX = 512
        const val MAX_SIZE_PX = 1024
    }
}
