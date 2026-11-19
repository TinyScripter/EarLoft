package de.kleinabersmart.earloft.wear

import android.graphics.Bitmap
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.wearsync.WearThumbnailFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thumbnails for the watch's lists in the "Phone" area (Wear concept 17): album cover, image of a subscription, cover of a
 * book or image of an episode, as a small JPEG over the file channel. The same images the app shows.
 */
@Singleton
class WearThumbnails @Inject constructor(
    private val artwork: ArtworkRepository,
    private val bookCovers: BookCovers,
    private val books: BookRepository,
) {
    /** `null` if the album, subscription or book doesn't exist or has no image. */
    suspend fun open(ref: WearThumbnailFile.Ref): ByteArray? {
        val size = WearThumbnailFile.SIZE_PX
        val bitmap = when (ref) {
            is WearThumbnailFile.Ref.Album -> artwork.albumCover(ref.albumId, size)
            is WearThumbnailFile.Ref.Feed -> bookCovers.feedThumbnail(ref.feedId, size)
            is WearThumbnailFile.Ref.Book -> books.idByRemote(ref.serverId, ref.remoteId)?.let { bookCovers.thumbnail(it, size, load = true) }
        } ?: return null
        return withContext(Dispatchers.Default) { bitmap.scaledTo(size).toJpeg() }
    }

    private fun Bitmap.scaledTo(maxPx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxPx) return this
        val factor = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1), true)
    }

    private fun Bitmap.toJpeg(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    private companion object {
        const val JPEG_QUALITY = 80
    }
}
