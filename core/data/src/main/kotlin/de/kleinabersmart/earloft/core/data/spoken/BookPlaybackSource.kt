package de.kleinabersmart.earloft.core.data.spoken

import android.net.Uri
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the player needs to start a book. */
data class PreparedBook(
    val book: Book,
    val timeline: BookTimeline,
    /** Media URL per file, in the order of [BookTimeline.tracks]; without credentials (concept 12.1). */
    val trackUrls: List<String>,
    /** Where playback starts after the server's state has been reconciled. */
    val startPositionMs: Long,
    /** The server had a newer state that differs noticeably from the one on this device. */
    val resumedFromServer: Boolean,
    val speed: Float,
    /**
     * Address per file for a cast receiver: that of the server or feed, even if the file is downloaded.
     * Without credentials; for Audiobookshelf the cast part adds the key ([absServerId]). Books and episodes from the device
     * only have their file (`content://`); the phone serves it itself (Chromecast stage 2).
     */
    val castUrls: List<String> = emptyList(),
    /** The Audiobookshelf server of the addresses; `null` for podcast episodes (public addresses). */
    val absServerId: String? = null,
    val castCoverUrl: String? = null,
    /**
     * Only with [BookPlaybackSource.restore]: since when playback has been paused (`book_progress.updatedAt`). The resume rule
     * then only applies when Play is pressed; `null` = already applied or no state.
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
    private val effects: SpokenEffectsRepository,
) {
    /**
     * For playback: reconciles progress with the server (without a connection the local state applies), applies the resume
     * rule and moves the book to the front of "Continue listening".
     * `null` if the book or its files can't be obtained.
     */
    suspend fun prepare(bookId: Long): PreparedBook? = build(bookId, forPlayback = true)

    /**
     * For restoring paused when the service starts: like [prepare], but without rewinding and without
     * writing. Otherwise every start not followed by playback would move the stored position back a bit and
     * restart the pause. The resume rule applies on the first Play ([PreparedBook.pausedSinceMs]).
     */
    suspend fun restore(bookId: Long): PreparedBook? = build(bookId, forPlayback = false)

    /**
     * Like [restore], but only from the database, without server and feed: for items that Media3 needs synchronously (an
     * episode comes back from the cast receiver after an app restart and has no address on the phone).
     */
    suspend fun rebuild(bookId: Long): PreparedBook? = build(bookId, forPlayback = false, offline = true)

    private suspend fun build(bookId: Long, forPlayback: Boolean, offline: Boolean = false): PreparedBook? {
        val book = repository.bookNow(bookId) ?: return null
        val pulled = if (offline) progressSync.local(bookId) else progressSync.pull(bookId)
        val episode = book.kind == BookKind.EPISODE
        // An episode's chapters come from a separate list; if it's missing or unreachable offline, we do without.
        if (episode && !offline) podcasts.ensureChapters(bookId)
        // Offline (restore, cast switch on the main thread): never ask the server, even if the details are outdated.
        val timeline = repository.timeline(bookId, fetch = !offline) ?: return null
        // Without streams (watch, Wear concept 16.1) only if every file is on the device; the player skips ones no longer available.
        if (!policy.allowStreaming && timeline.tracks.any { track -> !track.unavailable && track.localPath?.let(::File)?.isFile != true }) return null
        // A downloaded file takes precedence over the stream (episodes and books, also mixed if only part is downloaded).
        // Episodes and books from the device and from ARD Sounds have no Audiobookshelf server; their file identifier is the address itself.
        // Without streams everything is available as a file: the server is then not needed (watch without key, 16.2).
        val server = if (episode || book.isLocal || book.isArdSounds) {
            null
        } else {
            servers.server(book.serverId) ?: if (policy.allowStreaming) return null else null
        }
        val urls = timeline.tracks.map { track ->
            track.localPath?.let(::File)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
                // For episodes the file identifier is the audio address from the feed.
                ?: if (server == null) track.remoteFileId else api.fileUrl(server, book.remoteId, track.remoteFileId).toString()
        }
        // The phone serves files from the device itself when casting (concept 7.5).
        val castUrls = if (book.isLocal) urls else timeline.tracks.map { track ->
            if (server == null) track.remoteFileId else api.fileUrl(server, book.remoteId, track.remoteFileId).toString()
        }
        val progress = pulled.progress
        val playing = effects.effectsNow(bookId)
        // Skip intro (per subscription): only for an episode starting at 0 – not after rewinding to the beginning.
        val fresh = (progress?.takeUnless { it.finished }?.positionMs ?: 0L) == 0L
        // Without a duration in the feed (0) the end is unknown; then there's nothing to check the jump against.
        val introMs = playing.skipIntroMs.takeIf {
            episode && fresh && it > 0 && (timeline.durationMs <= 0 || it < timeline.durationMs - playing.skipOutroMs)
        }
        val startPositionMs = if (introMs != null) {
            introMs
        } else if (forPlayback) {
            resumePosition(
                progress = progress,
                nowMs = System.currentTimeMillis(),
                rule = (if (episode) settings.podcastResume else settings.bookResume).first(),
                chapters = if (episode) emptyList() else timeline.chapters,
                restartChapter = !episode && settings.restartChapter.first(),
            )
        } else {
            // Like resumePosition without a rule: a finished item starts from the beginning.
            progress?.takeUnless { it.finished }?.positionMs ?: 0L
        }
        val speed = playing.speed
        // After the reconcile: from now on the book is at the very front of "Continue listening" (episodes have their own row).
        if (forPlayback && !episode) {
            repository.touchProgress(bookId, startPositionMs, timeline.durationMs)
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
            castCoverUrl = server?.let { api.coverUrl(it, book.remoteId, CAST_COVER_PX).toString() }
                ?: repository.coverUrlOf(bookId).takeIf { book.isArdSounds },
            pausedSinceMs = if (forPlayback) null else progress?.takeUnless { it.finished }?.updatedAt,
        )
    }

    private companion object {
        const val CAST_COVER_PX = 512
    }
}
