package de.kleinabersmart.kleinaberlaut.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import de.kleinabersmart.kleinaberlaut.core.data.spoken.PreparedBook
import de.kleinabersmart.kleinaberlaut.core.model.AppUris
import de.kleinabersmart.kleinaberlaut.core.model.BookKind

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
                    .setMediaType(
                        if (book.kind == BookKind.EPISODE) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
                    )
                    .setExtras(
                        Bundle().apply {
                            putLong(EXTRA_BOOK_ID, book.id)
                            castUrls.getOrNull(index)?.let { putString(EXTRA_CAST_URL, it) }
                            timeline.tracks[index].mimeType?.let { putString(EXTRA_CAST_MIME, it) }
                            absServerId?.let { putString(EXTRA_CAST_ABS_SERVER, it) }
                            castCoverUrl?.let { putString(EXTRA_CAST_COVER, it) }
                        },
                    )
                    .build(),
            )
            .build()
    }

/** Ersetzt die Warteschlange durch das Buch und spielt es an der gespeicherten Stelle bzw. bei [startPositionMs]. */
internal fun Player.playBook(prepared: PreparedBook, startPositionMs: Long? = null, autoPlay: Boolean = true) {
    val start = prepared.timeline.locate(startPositionMs ?: prepared.startPositionMs)
    shuffleModeEnabled = false
    repeatMode = Player.REPEAT_MODE_OFF
    setMediaItems(prepared.toMediaItems(), start.trackIndex, start.offsetMs)
    setPlaybackSpeed(prepared.speed)
    prepare()
    if (autoPlay) play()
}

internal const val EXTRA_BOOK_ID = "de.kleinabersmart.kleinaberlaut.BOOK_ID"
private const val EXTRA_CAST_URL = "de.kleinabersmart.kleinaberlaut.CAST_URL"
private const val EXTRA_CAST_MIME = "de.kleinabersmart.kleinaberlaut.CAST_MIME"
private const val EXTRA_CAST_ABS_SERVER = "de.kleinabersmart.kleinaberlaut.CAST_ABS_SERVER"
private const val EXTRA_CAST_COVER = "de.kleinabersmart.kleinaberlaut.CAST_COVER"

/**
 * Wo ein Cast-Empfänger die Datei eines Buchs oder einer Folge findet. Die Adressen enthalten keine Zugangsdaten; bei
 * [absServerId] ≠ `null` gehört der Schlüssel dieses Audiobookshelf-Servers als `token` dazu.
 */
class SpokenCastSource(val url: String, val mimeType: String?, val coverUrl: String?, val absServerId: String?)

val MediaItem.spokenCastSource: SpokenCastSource?
    get() {
        val extras = mediaMetadata.extras ?: return null
        val url = extras.getString(EXTRA_CAST_URL) ?: return null
        return SpokenCastSource(url, extras.getString(EXTRA_CAST_MIME), extras.getString(EXTRA_CAST_COVER), extras.getString(EXTRA_CAST_ABS_SERVER))
    }
