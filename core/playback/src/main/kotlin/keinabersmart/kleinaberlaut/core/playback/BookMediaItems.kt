package keinabersmart.kleinaberlaut.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import keinabersmart.kleinaberlaut.core.data.spoken.PreparedBook
import keinabersmart.kleinaberlaut.core.model.AppUris

/**
 * Hörbücher in der Warteschlange (Konzept 12.4): ein `MediaItem` je Datei, `mediaId = book:<bookId>/<trackIndex>`.
 * Die URI ist die Medien-URL des Servers; den API-Key setzt der OkHttp-Interceptor.
 */
internal object BookIds {
    private const val PREFIX = "book:"
    private const val SEPARATOR = '/'

    fun mediaId(bookId: Long, trackIndex: Int) = "$PREFIX$bookId$SEPARATOR$trackIndex"

    fun bookId(mediaId: String): Long? =
        if (mediaId.startsWith(PREFIX)) mediaId.removePrefix(PREFIX).substringBefore(SEPARATOR).toLongOrNull() else null

    fun trackIndex(mediaId: String): Int? =
        if (mediaId.startsWith(PREFIX)) mediaId.substringAfter(SEPARATOR, "").toIntOrNull() else null
}

/** Die Buch-ID eines von [toMediaItems] erzeugten Items; `null` bei Musik. */
val MediaItem.bookId: Long? get() = BookIds.bookId(mediaId)

/** Index der Datei innerhalb des Buchs. */
val MediaItem.bookTrackIndex: Int? get() = BookIds.trackIndex(mediaId)

val MediaItem.isSpoken: Boolean get() = bookId != null

@OptIn(UnstableApi::class)
internal fun PreparedBook.toMediaItems(): List<MediaItem> =
    trackUrls.mapIndexed { index, url ->
        MediaItem.Builder()
            .setMediaId(BookIds.mediaId(book.id, index))
            .setUri(url)
            // Gleicher Cache-Schlüssel für dieselbe Datei, egal ob Server-URL oder Proxy davor.
            .setCustomCacheKey("book:${book.serverId}:${book.remoteId}:${timeline.tracks[index].remoteFileId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.author)
                    .setAlbumTitle(book.title)
                    .setArtworkUri(Uri.parse(AppUris.bookCover(book.id)))
                    .setDurationMs(timeline.tracks[index].durationMs)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .setExtras(Bundle().apply { putLong(EXTRA_BOOK_ID, book.id) })
                    .build(),
            )
            .build()
    }

internal const val EXTRA_BOOK_ID = "keinabersmart.kleinaberlaut.BOOK_ID"
