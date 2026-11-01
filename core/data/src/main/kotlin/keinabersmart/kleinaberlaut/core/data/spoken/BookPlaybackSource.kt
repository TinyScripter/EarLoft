package keinabersmart.kleinaberlaut.core.data.spoken

import android.net.Uri
import keinabersmart.kleinaberlaut.core.data.podcast.EpisodeQueue
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Alles, was der Player zum Starten eines Buchs braucht. */
data class PreparedBook(
    val book: Book,
    val timeline: BookTimeline,
    /** Medien-URL je Datei, in der Reihenfolge von [BookTimeline.tracks]; ohne Zugangsdaten (Konzept 12.1). */
    val trackUrls: List<String>,
    /** Wo die Wiedergabe beginnt, nachdem der Stand des Servers abgeglichen ist. */
    val startPositionMs: Long,
    /** Der Server hatte einen neueren Stand, der spürbar von dem auf diesem Gerät abweicht. */
    val resumedFromServer: Boolean,
    val speed: Float,
    /**
     * Adresse je Datei für einen Cast-Empfänger: immer die des Servers bzw. des Feeds, auch wenn die Datei geladen ist.
     * Ohne Zugangsdaten; bei Audiobookshelf ergänzt der Cast-Teil den Schlüssel ([absServerId]).
     */
    val castUrls: List<String> = emptyList(),
    /** Der Audiobookshelf-Server der Adressen; `null` bei Podcast-Folgen (öffentliche Adressen). */
    val absServerId: String? = null,
    val castCoverUrl: String? = null,
)

@Singleton
class BookPlaybackSource @Inject constructor(
    private val repository: BookRepository,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val progressSync: BookProgressSync,
    private val podcasts: PodcastRepository,
    private val queue: EpisodeQueue,
) {
    /**
     * Gleicht den Fortschritt mit dem Server ab (ohne Verbindung gilt der lokale Stand) und baut die Medien-URLs.
     * `null`, wenn das Buch oder seine Dateien nicht zu bekommen sind.
     */
    suspend fun prepare(bookId: Long): PreparedBook? {
        val book = repository.bookNow(bookId) ?: return null
        val pulled = progressSync.pull(bookId)
        val episode = book.kind == BookKind.EPISODE
        // Kapitel einer Folge kommen aus einer eigenen Liste; fehlt sie oder ist sie offline nicht erreichbar, geht es ohne.
        if (episode) podcasts.ensureChapters(bookId)
        val timeline = repository.timeline(bookId) ?: return null
        // Eine geladene Datei geht vor dem Stream (Folgen und Bücher, auch gemischt, wenn nur ein Teil geladen ist).
        // Folgen und Bücher vom Gerät haben keinen Audiobookshelf-Server; ihre Dateikennung ist die Adresse selbst.
        val server = if (episode || book.isLocal) null else servers.server(book.serverId) ?: return null
        val urls = timeline.tracks.map { track ->
            track.localPath?.let(::File)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
                // Bei Folgen ist die Dateikennung die Audio-Adresse aus dem Feed.
                ?: if (server == null) track.remoteFileId else api.fileUrl(server, book.remoteId, track.remoteFileId).toString()
        }
        // Ein Cast-Empfänger erreicht keine Datei auf diesem Gerät.
        val castUrls = if (book.isLocal) emptyList() else timeline.tracks.map { track ->
            if (server == null) track.remoteFileId else api.fileUrl(server, book.remoteId, track.remoteFileId).toString()
        }
        val progress = pulled.progress
        val startPositionMs = progress.startPosition()
        val speed = progress?.speed ?: 1f
        // Nach dem Abgleich: Das Buch steht ab jetzt ganz vorn in „Weiterhören“ (Folgen haben ihre eigene Zeile).
        if (!episode) {
            repository.touchProgress(bookId, startPositionMs, timeline.durationMs, speed)
            queue.moveToFront(bookId)
        }
        return PreparedBook(
            book = book,
            timeline = timeline,
            trackUrls = urls,
            startPositionMs = startPositionMs,
            resumedFromServer = pulled.noticeable,
            speed = speed,
            castUrls = castUrls,
            absServerId = server?.serverId,
            castCoverUrl = server?.let { api.coverUrl(it, book.remoteId, CAST_COVER_PX).toString() },
        )
    }

    /** Ein beendetes Buch beginnt von vorn; sonst geht es kurz vor der letzten Stelle weiter. */
    private fun BookProgress?.startPosition(): Long = when {
        this == null || finished -> 0L
        System.currentTimeMillis() - updatedAt > REWIND_AFTER_MS -> (positionMs - REWIND_MS).coerceAtLeast(0)
        else -> positionMs
    }

    private companion object {
        /** Nach längerer Pause ein Stück zurück, damit man wieder hineinfindet. */
        const val REWIND_AFTER_MS = 5 * 60 * 1000L
        const val REWIND_MS = 2_000L
        const val CAST_COVER_PX = 512
    }
}
