package de.kleinabersmart.earloft.ui.image

import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import de.kleinabersmart.earloft.core.data.spoken.AuthorImages
import de.kleinabersmart.earloft.core.model.AppUris
import java.io.IOException

/** Loads `earloft://cover/author/<name>` via [AuthorImages]; without an image, whatever is underneath stays (a book's cover). */
class AuthorImageFetcher(
    private val name: String,
    private val authorImages: AuthorImages,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bitmap = authorImages.image(name) ?: throw IOException("No image for author $name")
        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory(private val authorImages: AuthorImages) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != AppUris.SCHEME) return null
            val name = AppUris.parseAuthorImageName(data.toString()) ?: return null
            return AuthorImageFetcher(name, authorImages)
        }
    }
}
