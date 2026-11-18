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

/** Eine Datei eines Buchs, die nicht geladen und an der Quelle nicht mehr abrufbar ist ([BookTrack.unavailable]); der Player überspringt sie. */
val MediaItem.isUnavailablePart: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_UNAVAILABLE) == true

/** Podcast-Folge (sonst Buch); nur bei [isSpoken] aussagekräftig. */
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
            // Gleicher Cache-Schlüssel für dieselbe Datei, egal ob Server-URL oder Proxy davor.
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
 * Ersetzt die Warteschlange durch das Buch und spielt es an der gespeicherten Stelle bzw. bei [startPositionMs]. Zufall und
 * Wiederholen gehen erst danach aus: Vorher hielt der Player noch die Musik, und die App hätte „aus“ als Modus der
 * Musik-Warteschlange gespeichert.
 */
internal fun Player.playBook(prepared: PreparedBook, startPositionMs: Long? = null, autoPlay: Boolean = true) {
    val start = prepared.timeline.locate(startPositionMs ?: prepared.startPositionMs)
    setMediaItems(prepared.toMediaItems(), start.trackIndex, start.offsetMs)
    shuffleModeEnabled = false
    repeatMode = Player.REPEAT_MODE_OFF
    setPlaybackSpeed(prepared.speed)
    prepare()
    if (autoPlay) play()
}

internal const val EXTRA_BOOK_ID = "de.kleinabersmart.earloft.BOOK_ID"
private const val EXTRA_UNAVAILABLE = "de.kleinabersmart.earloft.UNAVAILABLE"

/**
 * Wo ein Cast-Empfänger die Datei eines Buchs oder einer Folge findet. Die Adressen enthalten kein Passwort, bei manchen
 * privaten Feeds aber einen Token im Pfad; bei [absServerId] ≠ `null` gehört der Schlüssel dieses Audiobookshelf-Servers
 * als `token` dazu. Bücher und Folgen vom Gerät haben eine `content://`-Adresse und kein [coverUrl]; beides liefert dann das
 * Handy selbst aus ([bookId] für das Cover).
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
 * Cast-Quellen je Buch bzw. Folge, nur im Speicher des App-Prozesses (Review D31): Die Metadaten der Items gehen an jeden
 * Controller, auch an fremde, und die Adresse einer Folge trägt bei manchen privaten Feeds den Token. Gefüllt von
 * [toMediaItems]; jeder Weg zu Buch-Items (Start, Fortsetzen, Neubau nach einer Cast-Sitzung) geht dort durch.
 */
internal object SpokenCastSources {
    private val byBook = ConcurrentHashMap<Long, List<SpokenCastSource?>>()

    fun put(bookId: Long, sources: List<SpokenCastSource?>) {
        byBook[bookId] = sources
    }

    fun get(bookId: Long, trackIndex: Int): SpokenCastSource? = byBook[bookId]?.getOrNull(trackIndex)
}
