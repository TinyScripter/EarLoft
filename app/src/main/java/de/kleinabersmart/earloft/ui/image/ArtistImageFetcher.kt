package de.kleinabersmart.earloft.ui.image

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.model.AppUris
import java.io.IOException

/** Loads `earloft://cover/artist/<id>` via [ArtworkRepository]; without an image (or with the option disabled) the placeholder stays. */
class ArtistImageFetcher(
    private val artistId: Long,
    private val artworkRepository: ArtworkRepository,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bitmap = artworkRepository.artistImage(artistId)
            ?: throw IOException("No image for artist $artistId")
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val artworkRepository: ArtworkRepository) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != AppUris.SCHEME) return null
            val artistId = AppUris.parseArtistImageId(data.toString()) ?: return null
            return ArtistImageFetcher(artistId, artworkRepository)
        }
    }
}
