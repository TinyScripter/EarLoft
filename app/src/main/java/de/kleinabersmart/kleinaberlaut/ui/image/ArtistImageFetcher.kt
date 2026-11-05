package de.kleinabersmart.kleinaberlaut.ui.image

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import de.kleinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import de.kleinabersmart.kleinaberlaut.core.model.AppUris
import java.io.IOException

/** Lädt `kal://cover/artist/<id>` über [ArtworkRepository]; ohne Bild (oder bei ausgeschalteter Option) bleibt der Platzhalter. */
class ArtistImageFetcher(
    private val artistId: Long,
    private val artworkRepository: ArtworkRepository,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bitmap = artworkRepository.artistImage(artistId)
            ?: throw IOException("Kein Bild für Interpret $artistId")
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
