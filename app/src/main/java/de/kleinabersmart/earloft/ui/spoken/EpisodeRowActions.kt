package de.kleinabersmart.earloft.ui.spoken

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.orOnError
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val books: BookRepository,
    private val progressSync: BookProgressSync,
    private val snackbars: Snackbars,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
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

    /** To the end of the queue or out of it, as in the episode menu ([removeFromQueue] says so). */
    suspend fun setQueued(bookId: Long, queued: Boolean) {
        if (queued) continueListening.append(bookId) else removeFromQueue(bookId)
    }

    /**
     * Takes the episode out of the queue and says so, with "Undo" back to its old place, as the menu and swiping do (review4 T38,
     * T39). A trial episode loses download and progress a moment later: there is no undo, it would bring back an empty entry
     * (review3 Q24).
     */
    suspend fun removeFromQueue(bookId: Long) {
        val before = continueListening.episodes.first()
        val title = before.firstOrNull { it.book.id == bookId }?.book?.title
        val trial = bookId in podcasts.trialEpisodeIds(listOf(bookId))
        continueListening.remove(bookId)
        if (title == null) return
        val order = before.map { it.book.id }
        notifyRemoved(title, undo = if (trial) null else suspend {
            continueListening.append(bookId)
            if (order.size > 1) continueListening.reorder(order, bookId)
        })
    }

    /**
     * Takes a book out of "Continue listening" (its state stays) and says so; "Undo" brings it back with the position it had
     * (review4 T39). Reporting it to Audiobookshelf happens in the background: the notice doesn't wait for the server, and leaving
     * the screen doesn't lose it (Y19).
     */
    suspend fun hideBook(bookId: Long) {
        // The currently loaded book stays in "Continue listening" (review5 P7).
        if (playback.state.value.spoken?.bookId == bookId) return
        val item = books.book(bookId).first() ?: return
        val before = item.progress ?: return
        if (before.hidden) return
        progressSync.hideFromContinueListening(listOf(bookId))
        notifyRemoved(item.book.title, undo = suspend {
            progressSync.record(bookId, before.positionMs, item.book.durationMs, finished = before.finished, force = true, hidden = false)
        })
    }

    private fun notifyRemoved(title: String, undo: (suspend () -> Unit)?) {
        val text = context.getString(R.string.queue_removed, title)
        if (undo == null) {
            snackbars.show(text)
        } else {
            snackbars.show(text, SnackbarDuration.Long, context.getString(R.string.action_undo)) {
                // Outlives the page, as the actions of the menu do.
                applicationScope.launch(Dispatchers.Main.immediate) { undo() }
            }
        }
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
