package de.kleinabersmart.earloft.core.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import de.kleinabersmart.earloft.core.data.spoken.PreparedBook
import de.kleinabersmart.earloft.core.model.authorLine
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Audiobooks in the queue (concept 12.4): one `MediaItem` per file, `mediaId = book:<bookId>/<trackIndex>`.
 * The URI is the server's media URL; the OkHttp interceptor adds the API key.
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

/** The book ID of an item created by [toMediaItems]; `null` for music. */
val MediaItem.bookId: Long? get() = BookIds.bookId(mediaId)

/** Index of the file within the book. */
val MediaItem.bookTrackIndex: Int? get() = BookIds.trackIndex(mediaId)

val MediaItem.isSpoken: Boolean get() = bookId != null

/** A file of a book that is not downloaded and no longer available at the source ([BookTrack.unavailable]); the player skips it. */
val MediaItem.isUnavailablePart: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_UNAVAILABLE) == true

/** Podcast episode (otherwise book); only meaningful if [isSpoken]. */
val MediaItem.isEpisode: Boolean get() = mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE

@OptIn(UnstableApi::class)
internal fun PreparedBook.toMediaItems(): List<MediaItem> {
    SpokenCastSources.put(book.id, trackUrls.indices.map { index ->
        castUrls.getOrNull(index)?.let { SpokenCastSource(it, timeline.tracks[index].mimeType, castCoverUrl, absServerId, book.id) }
    })
    return trackUrls.mapIndexed { index, url ->
        MediaItem.Builder()
            .setMediaId(BookIds.mediaId(book.id, index))
            .setUri(url)
            // Same cache key for the same file, whether server URL or a proxy in front of it.
            .setCustomCacheKey("book:${book.serverId}:${book.remoteId}:${timeline.tracks[index].remoteFileId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(book.authorLine)
                    .setAlbumTitle(book.title)
                    .setArtworkUri(CoverProvider.queueBookCoverUri(book.id))
                    .setDurationMs(timeline.tracks[index].durationMs)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(
                        if (book.kind == BookKind.EPISODE) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER,
                    )
                    .setExtras(
                        Bundle().apply {
                            putLong(EXTRA_BOOK_ID, book.id)
                            if (timeline.tracks[index].unavailable) putBoolean(EXTRA_UNAVAILABLE, true)
                        },
                    )
                    .build(),
            )
            .build()
    }
}

/**
 * Replaces the queue with the book and plays it at the saved position or at [startPositionMs]. Shuffle and
 * repeat are only turned off afterwards: before that the player still held the music, and the app would have saved "off"
 * as the mode of the music queue. Without [prepare] the player stays idle until play is pressed (Media3 prepares then): the
 * restore at service start then neither streams nor reads the sample table of a long M4B (review G32).
 */
internal fun Player.playBook(prepared: PreparedBook, startPositionMs: Long? = null, autoPlay: Boolean = true, prepare: Boolean = true) {
    val start = prepared.timeline.locate(startPositionMs ?: prepared.startPositionMs)
    setMediaItems(prepared.toMediaItems(), start.trackIndex, start.offsetMs)
    shuffleModeEnabled = false
    repeatMode = Player.REPEAT_MODE_OFF
    setPlaybackSpeed(prepared.speed)
    if (prepare || autoPlay) prepare()
    if (autoPlay) playSetItems()
}

/**
 * Starts playing what was just set with `setMediaItems`. Not with `play()` via a `MediaController`: the session then considers
 * a player without a current item empty and loads the last played via `onPlaybackResumption`. When casting,
 * the queue is still empty right after `setMediaItems` until the receiver reports it (e.g. after a book ended).
 */
internal fun Player.playSetItems() {
    playWhenReady = true
}

internal const val EXTRA_BOOK_ID = "de.kleinabersmart.earloft.BOOK_ID"
private const val EXTRA_UNAVAILABLE = "de.kleinabersmart.earloft.UNAVAILABLE"

/**
 * Where a cast receiver finds the file of a book or episode. The addresses contain no password, but with some
 * private feeds a token in the path; if [absServerId] ≠ `null`, this Audiobookshelf server's key belongs
 * to it as `token`. Books and episodes from the device have a `content://` address and no [coverUrl]; the phone then
 * serves both itself ([bookId] for the cover).
 */
class SpokenCastSource(
    val url: String,
    val mimeType: String?,
    val coverUrl: String?,
    val absServerId: String?,
    val bookId: Long? = null,
)

val MediaItem.spokenCastSource: SpokenCastSource?
    get() = bookId?.let { id -> bookTrackIndex?.let { SpokenCastSources.get(id, it) } }

/**
 * Cast sources per book or episode, only in the app process's memory (review D31): item metadata goes to every
 * controller, including foreign ones, and with some private feeds an episode's address carries the token. Filled by
 * [toMediaItems]; every path to book items (start, resume, rebuild after a cast session) goes through it.
 */
internal object SpokenCastSources {
    private val byBook = ConcurrentHashMap<Long, List<SpokenCastSource?>>()

    fun put(bookId: Long, sources: List<SpokenCastSource?>) {
        byBook[bookId] = sources
    }

    fun get(bookId: Long, trackIndex: Int): SpokenCastSource? = byBook[bookId]?.getOrNull(trackIndex)
}
