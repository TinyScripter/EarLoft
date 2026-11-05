package de.kleinabersmart.tinyamp.wear

import android.graphics.Bitmap
import de.kleinabersmart.tinyamp.core.data.repository.ArtworkRepository
import de.kleinabersmart.tinyamp.core.data.spoken.BookCovers
import de.kleinabersmart.tinyamp.core.data.spoken.BookRepository
import de.kleinabersmart.tinyamp.core.wearsync.WearThumbnailFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vorschaubilder für die Listen der Uhr im Bereich „Telefon“ (Wear-Konzept 17): Album-Cover, Bild eines Abos, Cover eines
 * Buchs bzw. Bild einer Folge, als kleines JPEG über den Datei-Kanal. Dieselben Bilder, die die App zeigt.
 */
@Singleton
class WearThumbnails @Inject constructor(
    private val artwork: ArtworkRepository,
    private val bookCovers: BookCovers,
    private val books: BookRepository,
) {
    /** `null`, wenn es das Album, Abo oder Buch nicht gibt oder es kein Bild hat. */
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
