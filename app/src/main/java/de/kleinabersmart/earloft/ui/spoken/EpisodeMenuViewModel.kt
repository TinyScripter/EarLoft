package de.kleinabersmart.earloft.ui.spoken

import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.BookDownload
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.ServicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EpisodeMenuViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val continueListening: ContinueListening,
    private val downloads: EpisodeDownloads,
    private val playback: PlaybackConnection,
    private val books: BookRepository,
    private val bookDownloads: BookDownloads,
    private val progressSync: BookProgressSync,
    private val servicePlayer: ServicePlayer,
    private val launcher: BookLauncher,
    private val ardBooks: ArdBooks,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    /**
     * Actions complete in the app scope on the main thread: the snackbar with "Undo" outlives the page, and an
     * action over many episodes would otherwise abort midway on "Back" (review D13).
     */
    private fun act(block: suspend CoroutineScope.() -> Unit): Job = applicationScope.launch(Dispatchers.Main.immediate, block = block)

    /** The book or episode in the player; a book from ARD Sounds can't be removed then. */
    val loadedBookId: StateFlow<Long?> = playback.state
        .map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Download state of a book with its size, for the question before deleting it (review G61). */
    fun bookDownload(bookId: Long): Flow<BookDownload> = books.download(bookId)

    /** The player is playing right now (not: paused); for the hint when swiping the loaded episode (review G60). */
    fun isPlaying(): Boolean = playback.state.value.isPlaying

    /** Remove a book from ARD Sounds including downloads from the library (`books_improvements.md` M9). */
    fun removeFromLibrary(item: BookWithProgress) {
        val id = item.book.id
        if (!item.book.isArdSounds || loadedBookId.value == id) return
        act {
            bookDownloads.remove(id)
            ardBooks.remove(id)
        }
    }

    /** Episodes currently in the "Continue listening" row (the queue). */
    val queued: StateFlow<Set<Long>> = continueListening.episodes
        .map { list -> list.mapTo(HashSet()) { it.book.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Episodes and books with downloaded files or a running or waiting download (then there is something to delete); also
     * partially downloaded books whose state is "failed" after a failed file.
     */
    val downloaded: StateFlow<Set<Long>> = combine(
        podcasts.downloadedIds(),
        books.downloads().map { map ->
            // A removed download has nothing left to delete: the menu offers "Download" again, as the book page does.
            map.filterValues {
                it.state != TrackDownload.NONE && it.state != TrackDownload.REMOVED && (it.state != TrackDownload.FAILED || it.partial)
            }.keys
        },
    ) { episodes, bookIds -> episodes + bookIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Episodes with a fully downloaded file; only those can be shared as a file. */
    val downloadedEpisodes: StateFlow<Set<Long>> = podcasts.downloadedIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The frontmost episode of the row (the current one or the one playback continues with); "Play next" goes after it. */
    val queueHead: StateFlow<Long?> = continueListening.episodes
        .map { it.firstOrNull()?.book?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** If the episode is already the second in the row, nothing changes. */
    val queueSecond: StateFlow<Long?> = continueListening.episodes
        .map { it.getOrNull(1)?.book?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Trial subscriptions from the preview: their episodes can't be starred (the subscription disappears when leaving the page). */
    val trialFeeds: StateFlow<Set<String>> = podcasts.trialFeedIds
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun playNext(bookId: Long) {
        act { continueListening.playNext(bookId) }
    }

    /** Star or unstar, also for several episodes of the multi-selection. */
    fun setStarred(items: List<BookWithProgress>, starred: Boolean) {
        act { podcasts.setStarred(items.filter { it.isEpisode }.map { it.book.id }, starred) }
    }

    /** Into the queue or out of it; taking it out offers [onUndo], which puts it back at its old place. */
    fun setQueued(bookId: Long, queued: Boolean, onUndo: (suspend () -> Unit) -> Unit = {}) {
        act {
            if (queued) {
                continueListening.append(bookId)
            } else {
                val before = continueListening.episodes.first().map { it.book.id }
                continueListening.remove(bookId)
                onUndo {
                    continueListening.append(bookId)
                    if (before.size > 1) continueListening.reorder(before, bookId)
                }
            }
        }
    }

    /**
     * Books also report the progress to Audiobookshelf, episodes only have the local progress. A book marked "unplayed" moves from
     * "Continue listening" back to "Recently added".
     */
    fun setPlayed(item: BookWithProgress, played: Boolean, onUndo: (suspend () -> Unit) -> Unit = {}) {
        val id = item.book.id
        // Loaded in the player: it pauses and writes the progress itself, otherwise it would overwrite it on the next save.
        if (played && servicePlayer.markFinished(id, playNext = false)) return
        act {
            if (item.isEpisode) {
                onUndo(podcasts.setPlayed(listOf(id), played))
            } else {
                val duration = item.book.durationMs
                progressSync.record(id, if (played) duration else 0, duration, finished = played, force = true, hidden = !played)
                onUndo { restoreBookProgress(item) }
            }
            // "Unplayed" on the loaded entry: the player would otherwise write its position back on the next save.
            if (!played) seekLoadedToStart(item)
        }
    }

    /** "Undo" for a book: its progress as it was before (with Audiobookshelf reported like any other change). */
    private suspend fun restoreBookProgress(item: BookWithProgress) {
        val before = item.progress
        progressSync.record(
            item.book.id,
            before?.positionMs ?: 0,
            item.book.durationMs,
            finished = before?.finished == true,
            force = true,
            hidden = before?.hidden ?: true,
        )
    }

    /**
     * If [item] is in the player, jump to its very beginning. A book is one media item per file: `seekTo(0)` would only go to
     * the start of the current file, and the next save would write that back (review G19).
     */
    private fun seekLoadedToStart(item: BookWithProgress) {
        if (playback.state.value.spoken?.bookId != item.book.id) return
        if (item.isEpisode) playback.seekTo(0) else playback.seekToTrack(0, 0)
    }

    /** "Mark as played and play next" (episodes only): afterwards the next episode of the queue. */
    fun setPlayedAndPlayNext(item: BookWithProgress) {
        val id = item.book.id
        if (servicePlayer.markFinished(id, playNext = true)) return
        act {
            podcasts.setPlayed(id, true)
            continueListening.next(id)?.let(launcher::play)
        }
    }

    fun resetPosition(item: BookWithProgress, onUndo: (suspend () -> Unit) -> Unit = {}) {
        val bookId = item.book.id
        // A book that was never started has nothing to reset. A reset one leaves "Continue listening" like "unplayed".
        if (!item.isEpisode && item.progress == null) return
        act {
            if (item.isEpisode) {
                // The same as "unplayed" (`PodcastRepository.resetPosition`), but with the way back.
                onUndo(podcasts.setPlayed(listOf(bookId), false))
            } else {
                progressSync.record(bookId, 0, item.book.durationMs, finished = false, force = true, hidden = true)
                onUndo { restoreBookProgress(item) }
            }
            // If the episode is currently playing, playback also jumps to the start.
            seekLoadedToStart(item)
        }
    }

    /** "Remove from Continue listening" (books only): the progress stays; with Audiobookshelf on all devices. */
    fun hideFromContinueListening(item: BookWithProgress) {
        act { progressSync.hideFromContinueListening(item.book.id) }
    }

    fun removeDownload(item: BookWithProgress) {
        act { if (item.isEpisode) downloads.remove(item.book.id) else bookDownloads.remove(item.book.id) }
    }

    // --- Multi-selection and swiping (concept 12.5): the same actions for several episodes, in list order.

    /**
     * "Played" or "unplayed" for all [items]. The player itself pauses the episode loaded in it (as with the single
     * menu item); for the others [onUndo] gets a way back to the previous state.
     */
    fun setPlayed(items: List<BookWithProgress>, played: Boolean, onUndo: (suspend () -> Unit) -> Unit = {}) {
        val ids = items.map { it.book.id }.filter { !played || !servicePlayer.markFinished(it, playNext = false) }
        if (ids.isEmpty()) return
        act { onUndo(podcasts.setPlayed(ids, played)) }
    }

    fun undo(restore: suspend () -> Unit) {
        act { restore() }
    }

    /** To the end of the queue, in the order of the selection; whatever is already in it stays where it is. */
    fun append(items: List<BookWithProgress>) {
        val inQueue = queued.value
        act { items.filter { it.book.id !in inQueue }.forEach { continueListening.append(it.book.id) } }
    }

    /** Directly after the frontmost episode, in the order of the selection (the frontmost one itself stays where it is). */
    fun playNext(items: List<BookWithProgress>) {
        act { continueListening.playNext(items.map { it.book.id }) }
    }

    fun removeFromQueue(items: List<BookWithProgress>) {
        act { items.forEach { continueListening.remove(it.book.id) } }
    }

    fun removeDownloads(items: List<BookWithProgress>) {
        val present = downloaded.value
        act { items.filter { it.book.id in present }.forEach { downloads.remove(it.book.id) } }
    }

    /** Downloads all episodes not yet downloaded (not those from the device); [onHint] as with [download], at most once. */
    fun download(items: List<BookWithProgress>, onHint: (Int) -> Unit) {
        val present = downloaded.value
        val missing = items.filter { !it.book.isLocal && it.book.id !in present }
        act {
            val starts = missing.map { downloads.download(it.book.id) }
            when {
                DownloadStart.OFFLINE in starts -> onHint(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI in starts -> onHint(R.string.podcast_download_waiting_wifi)
            }
        }
    }

    /** [onHint] receives a text if the download doesn't start right away (offline, waiting for Wi-Fi). */
    fun download(item: BookWithProgress, onHint: (Int) -> Unit) {
        act {
            val start = if (item.isEpisode) downloads.download(item.book.id) else bookDownloads.download(item.book.id)
            when (start) {
                DownloadStart.OFFLINE -> onHint(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI ->
                    onHint(if (item.isEpisode) R.string.podcast_download_waiting_wifi else R.string.book_download_waiting_wifi)
                DownloadStart.STARTED -> Unit
            }
        }
    }
}
