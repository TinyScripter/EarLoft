package keinabersmart.kleinaberlaut.core.data.spoken

import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
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
)

@Singleton
class BookPlaybackSource @Inject constructor(
    private val repository: BookRepository,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val progressSync: BookProgressSync,
    private val podcasts: PodcastRepository,
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
        val urls = if (episode) {
            // Bei Folgen ist die Dateikennung die Audio-Adresse aus dem Feed.
            timeline.tracks.map { it.remoteFileId }
        } else {
            val server = servers.server(book.serverId) ?: return null
            timeline.tracks.map { api.fileUrl(server, book.remoteId, it.remoteFileId).toString() }
        }
        val progress = pulled.progress
        return PreparedBook(
            book = book,
            timeline = timeline,
            trackUrls = urls,
            startPositionMs = progress.startPosition(),
            resumedFromServer = pulled.noticeable,
            speed = progress?.speed ?: 1f,
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
    }
}
