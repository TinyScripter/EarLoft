package de.kleinabersmart.kleinaberlaut.core.data.spoken

import android.net.Uri
import de.kleinabersmart.kleinaberlaut.core.data.podcast.EpisodeQueue
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import de.kleinabersmart.kleinaberlaut.core.data.repository.PlaybackPolicy
import de.kleinabersmart.kleinaberlaut.core.model.Book
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.model.BookTimeline
import de.kleinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import kotlinx.coroutines.flow.first
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
    /**
     * Nur bei [BookPlaybackSource.restore]: seit wann pausiert ist (`book_progress.updatedAt`). Die Regel zum Fortsetzen
     * greift dann erst beim Drücken von Play; `null` = schon angewendet bzw. kein Stand.
     */
    val pausedSinceMs: Long? = null,
)

@Singleton
class BookPlaybackSource @Inject constructor(
    private val repository: BookRepository,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val progressSync: BookProgressSync,
    private val podcasts: PodcastRepository,
    private val queue: EpisodeQueue,
    private val settings: SpokenSettings,
    private val policy: PlaybackPolicy,
) {
    /**
     * Zum Abspielen: gleicht den Fortschritt mit dem Server ab (ohne Verbindung gilt der lokale Stand), wendet die Regel
     * zum Fortsetzen an und stellt das Buch nach vorn in „Weiterhören“.
     * `null`, wenn das Buch oder seine Dateien nicht zu bekommen sind.
     */
    suspend fun prepare(bookId: Long): PreparedBook? = build(bookId, forPlayback = true)

    /**
     * Zum pausierten Wiederherstellen beim Start des Service: wie [prepare], aber ohne Zurückspulen und ohne zu
     * schreiben. Sonst rückte jeder Start, auf den kein Abspielen folgt, die gespeicherte Position ein Stück zurück und
     * setzte die Pause neu an. Die Regel zum Fortsetzen greift beim ersten Play ([PreparedBook.pausedSinceMs]).
     */
    suspend fun restore(bookId: Long): PreparedBook? = build(bookId, forPlayback = false)

    private suspend fun build(bookId: Long, forPlayback: Boolean): PreparedBook? {
        val book = repository.bookNow(bookId) ?: return null
        val pulled = progressSync.pull(bookId)
        val episode = book.kind == BookKind.EPISODE
        // Kapitel einer Folge kommen aus einer eigenen Liste; fehlt sie oder ist sie offline nicht erreichbar, geht es ohne.
        if (episode) podcasts.ensureChapters(bookId)
        val timeline = repository.timeline(bookId) ?: return null
        // Ohne Streams (Uhr, Wear-Konzept 16.1) nur, wenn jede Datei auf dem Gerät liegt.
        if (!policy.allowStreaming && timeline.tracks.any { track -> track.localPath?.let(::File)?.isFile != true }) return null
        // Eine geladene Datei geht vor dem Stream (Folgen und Bücher, auch gemischt, wenn nur ein Teil geladen ist).
        // Folgen und Bücher vom Gerät haben keinen Audiobookshelf-Server; ihre Dateikennung ist die Adresse selbst.
        // Ohne Streams liegt alles als Datei vor: Der Server wird dann nicht gebraucht (Uhr ohne Schlüssel, 16.2).
        val server = if (episode || book.isLocal) null else servers.server(book.serverId) ?: if (policy.allowStreaming) return null else null
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
        val startPositionMs = if (forPlayback) {
            resumePosition(
                progress = progress,
                nowMs = System.currentTimeMillis(),
                rule = (if (episode) settings.podcastResume else settings.bookResume).first(),
                chapters = if (episode) emptyList() else timeline.chapters,
                restartChapter = !episode && settings.restartChapter.first(),
            )
        } else {
            // Wie resumePosition ohne Regel: Ein beendetes Stück beginnt von vorn.
            progress?.takeUnless { it.finished }?.positionMs ?: 0L
        }
        val speed = progress?.speed ?: 1f
        // Nach dem Abgleich: Das Buch steht ab jetzt ganz vorn in „Weiterhören“ (Folgen haben ihre eigene Zeile).
        if (forPlayback && !episode) {
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
            pausedSinceMs = if (forPlayback) null else progress?.takeUnless { it.finished }?.updatedAt,
        )
    }

    private companion object {
        const val CAST_COVER_PX = 512
    }
}
