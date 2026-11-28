package de.kleinabersmart.earloft.wear.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.LocalContentColor
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.wearsync.WearThumbnailFile
import de.kleinabersmart.earloft.wear.download.PhoneFileFetcher
import de.kleinabersmart.earloft.wear.sync.PhoneConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where the thumbnail of a list entry comes from. */
sealed interface CoverRef {
    data class Album(val albumId: Long) : CoverRef

    /** Book or episode; without its own image, the subscription's. */
    data class Book(val bookId: Long) : CoverRef

    /** Album, subscription, book or episode of the phone, "Phone" section (Wear concept 17). */
    data class Phone(val thumbnail: WearThumbnailFile.Ref) : CoverRef
}

/**
 * Thumbnails for the watch's lists: small, from the files that come with the downloads (album covers, images of books
 * and episodes), without a server. The most recently shown ones stay in memory so that scrolling does not decode again every time.
 *
 * The watch only fetches images from the phone (albums, subscriptions, books, episodes) once the entry becomes visible, small via the
 * file channel ([PhoneFileFetcher.fetchThumbnail]), and stores them in the cache directory; at most [PHONE_PARALLEL] at a time,
 * so that fast scrolling does not clog the connection. Whatever the phone does not deliver (no file or transfer
 * cancelled), it only asks for again after [PHONE_RETRY_MS].
 */
@Singleton
class ListCovers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val coverFiles: CoverFiles,
    private val bookCovers: BookCovers,
    private val phoneFiles: PhoneFileFetcher,
    private val phone: PhoneConnection,
) {
    private val cache = LruCache<CoverRef, ImageBitmap>(CACHE_ENTRIES)
    private val phoneRequests = Semaphore(PHONE_PARALLEL)
    /** Images the phone did not deliver, with the timestamp (`elapsedRealtime`). */
    private val phoneMissing = ConcurrentHashMap<WearThumbnailFile.Ref, Long>()

    /** Is the image already in memory? Then the list shows it without a placeholder. */
    fun cached(ref: CoverRef): ImageBitmap? = cache.get(ref)

    suspend fun load(ref: CoverRef): ImageBitmap? {
        cache.get(ref)?.let { return it }
        val bitmap = when (ref) {
            is CoverRef.Album -> withContext(Dispatchers.IO) { coverFiles.readScaled(ref.albumId, THUMBNAIL_PX) }
            is CoverRef.Book -> bookCovers.thumbnail(ref.bookId, THUMBNAIL_PX)
            is CoverRef.Phone -> fromPhone(ref.thumbnail)
        } ?: return null
        return bitmap.asImageBitmap().also { cache.put(ref, it) }
    }

    /**
     * The image from the phone, kept in the cache directory. One older than [PHONE_MAX_AGE_MS] is fetched again when the phone is
     * there: the key is an ID of the phone's database, which hands it out again after being created anew, and a new cover on the
     * phone never came either (review5 P68). Without the phone the old one stays.
     */
    private suspend fun fromPhone(thumbnail: WearThumbnailFile.Ref): Bitmap? {
        val file = File(context.cacheDir, "$PHONE_DIRECTORY/${fileName(thumbnail)}.jpg")
        val current = withContext(Dispatchers.IO) { file.isFile && System.currentTimeMillis() - file.lastModified() < PHONE_MAX_AGE_MS }
        if (!current) fetchFromPhone(thumbnail, file)
        return withContext(Dispatchers.IO) { if (file.isFile) BitmapFactory.decodeFile(file.path) else null }
    }

    private suspend fun fetchFromPhone(thumbnail: WearThumbnailFile.Ref, file: File) {
        val missedAt = phoneMissing[thumbnail]
        if (missedAt != null && SystemClock.elapsedRealtime() - missedAt < PHONE_RETRY_MS) return
        // Without a phone, do not remember anything as "missing": the image should come after connecting.
        phone.connectedPhoneNodeId() ?: return
        val bytes = phoneRequests.withPermit { phoneFiles.fetchThumbnail(thumbnail) }
        if (bytes == null) {
            phoneMissing[thumbnail] = SystemClock.elapsedRealtime()
            return
        }
        phoneMissing.remove(thumbnail)
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    /** Identifiers of episodes can be long addresses: use their hash as the file name. */
    private fun fileName(thumbnail: WearThumbnailFile.Ref): String =
        MessageDigest.getInstance("SHA-256").digest(WearThumbnailFile.uid(thumbnail).toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** A good twice the size of the image in the list (40 dp), at around 2 px per dp. */
        const val THUMBNAIL_PX = 96
        /** Around 37 KB per image (from the phone 120 px: around 58 KB): together under 5 MB. */
        const val CACHE_ENTRIES = 80
        const val PHONE_PARALLEL = 2
        const val PHONE_RETRY_MS = 10 * 60_000L
        const val PHONE_MAX_AGE_MS = 7 * 24 * 60 * 60_000L
        const val PHONE_DIRECTORY = "phone_thumbnails"
    }
}

/** Set by the activity in the app; missing in previews, then it stays with the placeholder. */
val LocalListCovers = staticCompositionLocalOf<ListCovers?> { null }

private val THUMBNAIL_SIZE = 40.dp
private val THUMBNAIL_SHAPE = RoundedCornerShape(8.dp)
private const val PLACEHOLDER_ALPHA = 0.15f

/** Thumbnail of a list entry; an empty area until it is loaded and if there is none. */
@Composable
fun CoverThumbnail(ref: CoverRef, modifier: Modifier = Modifier) {
    val covers = LocalListCovers.current
    val image by produceState(covers?.cached(ref), ref, covers) {
        if (value == null && covers != null) value = covers.load(ref)
    }
    val shaped = modifier.size(THUMBNAIL_SIZE).clip(THUMBNAIL_SHAPE)
    val loaded = image
    if (loaded != null) {
        Image(loaded, contentDescription = null, modifier = shaped, contentScale = ContentScale.Crop)
    } else {
        // Slightly set off from the button's color, whatever it is.
        Box(shaped.background(LocalContentColor.current.copy(alpha = PLACEHOLDER_ALPHA)))
    }
}
