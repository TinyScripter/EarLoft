package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * What the episode lists (subscription page, "New", "Starred", history) and the episode menu do with an episode the same way:
 * download, queue, play. Each of them used to carry its own copy, and the rule for trial subscriptions got lost in one of them
 * (review2 J46, J4). Unscoped: every ViewModel gets its own instance and runs the suspending actions in its scope.
 */
class EpisodeRowActions @Inject constructor(
    private val podcasts: PodcastRepository,
    private val continueListening: ContinueListening,
    private val downloads: EpisodeDownloads,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
) {
    /** Episodes currently in "Continue listening" (the queue), including those that got there automatically. */
    val queued: Flow<Set<Long>> = continueListening.episodes
        .map { list -> list.mapTo(HashSet()) { it.book.id } }
        .orOnError(emptySet())

    /** The episode currently playing (not: paused). */
    val playingBookId: Flow<Long?> = playback.state.map { if (it.isPlaying) it.spoken?.bookId else null }

    /** The loaded episode pauses or resumes, any other one starts (review A3). */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }

    /** To the end of the queue or out of it, as in the episode menu. */
    suspend fun setQueued(bookId: Long, queued: Boolean) {
        if (queued) continueListening.append(bookId) else continueListening.remove(bookId)
    }

    /**
     * Downloads [bookIds] in this order. Episodes of a trial subscription first go to the end of the queue: there they keep the
     * download, otherwise the trial cleanup deletes it again a second later (concept 12.10, review2 J4).
     * @return a hint (string resource) if a download doesn't start right away, at most one for all.
     */
    suspend fun download(bookIds: List<Long>): Int? {
        if (bookIds.isEmpty()) return null
        val trial = podcasts.trialEpisodeIds(bookIds)
        bookIds.filter { it in trial }.forEach { continueListening.append(it) }
        return downloadHint(bookIds.map { downloads.download(it) })
    }

    suspend fun download(bookId: Long): Int? = download(listOf(bookId))

    suspend fun removeDownload(bookId: Long) = downloads.remove(bookId)
}

/** The text for downloads that don't start right away; offline outweighs waiting for Wi-Fi. [book]: the hint names Settings → Books. */
internal fun downloadHint(starts: Collection<DownloadStart>, book: Boolean = false): Int? = when {
    DownloadStart.OFFLINE in starts -> R.string.podcast_download_offline
    DownloadStart.WAITING_FOR_WIFI in starts -> if (book) R.string.book_download_waiting_wifi else R.string.podcast_download_waiting_wifi
    else -> null
}
