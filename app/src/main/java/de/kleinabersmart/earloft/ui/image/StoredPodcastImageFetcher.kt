package de.kleinabersmart.earloft.ui.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Subscription images via their address (`PodcastArt`): if the image is already on the device ([BookCovers], downloaded in advance
 * for offline use), it comes from there, otherwise Coil's network fetcher takes over. That way subscriptions show their images even offline.
 */
class StoredPodcastImageFetcher(
    private val url: String,
    private val bookCovers: BookCovers,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val file = withContext(Dispatchers.IO) { bookCovers.storedImage(url) } ?: return null
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val bookCovers: BookCovers) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "http" && data.scheme != "https") return null
            return StoredPodcastImageFetcher(data.toString(), bookCovers)
        }
    }
}
