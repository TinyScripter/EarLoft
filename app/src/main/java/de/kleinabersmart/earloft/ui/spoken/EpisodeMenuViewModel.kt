package de.kleinabersmart.earloft.ui.spoken

import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.RecentlyAdded
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.BookDownload
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.download.BookDownloads
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
import de.kleinabersmart.earloft.ui.orOnError
import javax.inject.Inject

@HiltViewModel
class EpisodeMenuViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val continueListening: ContinueListening,
    private val recentlyAdded: RecentlyAdded,
    private val downloads: EpisodeDownloads,
    private val playback: PlaybackConnection,
    private val books: BookRepository,
    private val bookDownloads: BookDownloads,
    private val progressSync: BookProgressSync,
    private val servicePlayer: ServicePlayer,
    private val launcher: BookLauncher,
    private val ardBooks: ArdBooks,
    private val rows: EpisodeRowActions,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    /**
     * Actions complete in the app scope on the main thread: the snackbar with "Undo" outlives the page, and an
     * action over many episodes would otherwise abort midway on "Back" (review D13).
     */
    private fun act(block: suspend CoroutineScope.() -> Unit): Job = applicationScope.launch(Dispatchers.Main.immediate, block = block)

    /** The book or episode in the player; a book from ARD Sounds can't be removed then. */
    private val loadedBookIdSource = playback.state.map { it.spoken?.bookId }.orOnError(null)
    val loadedBookId: StateFlow<Long?> = loadedBookIdSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    val queued: StateFlow<Set<Long>> = rows.queued
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Episodes and books with downloaded files or a running or waiting download (then there is something to delete); also
     * partially downloaded books whose state is "failed" after a failed file.
     */
    private val downloadedSource = combine(
        podcasts.downloadedIds(),
        books.downloads().map { map ->
            // A removed download has nothing left to delete: the menu offers "Download" again, as the book page does.
            map.filterValues {
                it.state != TrackDownload.NONE && it.state != TrackDownload.REMOVED && (it.state != TrackDownload.FAILED || it.partial)
            }.keys
        },
    ) { episodes, bookIds -> episodes + bookIds }
        .orOnError(emptySet())
    val downloaded: StateFlow<Set<Long>> = downloadedSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Episodes with a fully downloaded file; only those can be shared as a file. */
    private val downloadedEpisodesSource = podcasts.downloadedIds().orOnError(emptySet())

    /** The frontmost episode of the row (the current one or the one playback continues with); "Play next" goes after it. */
    val queueHead: StateFlow<Long?> = continueListening.episodes
        .map { it.firstOrNull()?.book?.id }
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Trial subscriptions from the preview: their episodes can't be starred (the subscription disappears when leaving the page). */
    private val trialFeedsSource = podcasts.trialFeedIds.map { it.toSet() }.orOnError(emptySet())
    val trialFeeds: StateFlow<Set<String>> = trialFeedsSource.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Everything the episode menu shows, at once and `null` until it is known: the menu opens only then, instead of showing
     * defaults for a moment ("Download" for a downloaded episode, "Add to queue" for a queued one; review4 Y20).
     */
    val facts: StateFlow<EpisodeMenuFacts?> = combine(
        rows.queued,
        downloadedSource,
        downloadedEpisodesSource,
        continueListening.episodes.map { it.firstOrNull()?.book?.id to it.getOrNull(1)?.book?.id }.orOnError(null to null),
        combine(trialFeedsSource, loadedBookIdSource, ::Pair),
    ) { queued, downloaded, downloadedEpisodes, (head, second), (trialFeeds, loaded) ->
        EpisodeMenuFacts(queued, downloaded, downloadedEpisodes, head, second, trialFeeds, loaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /** "Remove from Recently added": only out of that row, "Continue listening" stays as it is (concept 12.5). */
    fun hideFromRecent(bookId: Long, onUndo: (suspend () -> Unit) -> Unit = {}) {
        act {
            recentlyAdded.hide(bookId)
            onUndo { recentlyAdded.unhide(bookId) }
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
            val spot = if (played) null else loadedSpot(item)
            val restore: suspend () -> Unit = if (item.isEpisode) {
                podcasts.setPlayed(listOf(id), played)
            } else {
                val duration = item.book.durationMs
                progressSync.record(id, if (played) duration else 0, duration, finished = played, force = true, hidden = !played)
                ({ restoreBookProgress(item) })
            }
            // "Unplayed" on the loaded entry: the player would otherwise write its position back on the next save.
            if (!played) seekLoadedToStart(item)
            onUndo {
                restore()
                spot?.let { returnLoaded(item, it) }
            }
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

    /** Where the player stands in [item] (file, position in it and in the whole book), if it is loaded. */
    private suspend fun loadedSpot(item: BookWithProgress): LoadedSpot? {
        val spoken = playback.state.value.spoken?.takeIf { it.bookId == item.book.id } ?: return null
        val offset = playback.currentPositionMs
        val bookPosition = if (item.isEpisode) {
            offset
        } else {
            books.timeline(item.book.id, fetch = false)?.toBookPosition(spoken.trackIndex, offset) ?: return null
        }
        return LoadedSpot(spoken.trackIndex, offset, bookPosition)
    }

    /**
     * "Undo" after [seekLoadedToStart]: the player goes back to where it was, a book into the right file (review H16). Its
     * place also becomes the stored state: the list's state can be older (seeking while paused saves nothing), and a paused
     * player follows the stored state (`BookPlaybackListener.followStoredProgress`), it would be pulled back there.
     */
    private suspend fun returnLoaded(item: BookWithProgress, spot: LoadedSpot) {
        if (playback.state.value.spoken?.bookId != item.book.id) return
        val duration = item.book.durationMs.takeIf { it > 0 } ?: item.progress?.durationMs ?: 0
        progressSync.record(item.book.id, spot.bookPositionMs, duration, finished = false, force = true)
        if (item.isEpisode) playback.seekTo(spot.positionMs) else playback.seekToTrack(spot.trackIndex, spot.positionMs)
    }

    private class LoadedSpot(val trackIndex: Int, val positionMs: Long, val bookPositionMs: Long)

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
            val spot = loadedSpot(item)
            val restore: suspend () -> Unit = if (item.isEpisode) {
                // The same as "unplayed" (`PodcastRepository.resetPosition`), but with the way back.
                podcasts.setPlayed(listOf(bookId), false)
            } else {
                progressSync.record(bookId, 0, item.book.durationMs, finished = false, force = true, hidden = true)
                ({ restoreBookProgress(item) })
            }
            // If the episode is currently playing, playback also jumps to the start.
            seekLoadedToStart(item)
            onUndo {
                restore()
                spot?.let { returnLoaded(item, it) }
            }
        }
    }

    /** "Remove from Continue listening" (books only): the progress stays; with Audiobookshelf on all devices. */
    fun hideFromContinueListening(item: BookWithProgress) {
        act { rows.hideBook(item.book.id) }
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

    /**
     * Removes [items] from the queue, keeping the currently loaded episode as "Clear" does (review5 P45).
     * [onDone] receives the actual number removed and an undo action that restores non-trial episodes to their old positions.
     */
    fun removeFromQueue(
        items: List<BookWithProgress>,
        onDone: (removedCount: Int, undo: (suspend () -> Unit)?) -> Unit = { _, _ -> },
    ) {
        act {
            val loadedId = playback.state.value.spoken?.bookId
            val before = continueListening.episodes.first().map { it.book.id }
            val inQueue = before.toHashSet()
            val targets = items.map { it.book.id }.distinct().filter { it in inQueue && it != loadedId }
            if (targets.isEmpty()) return@act
            val trial = podcasts.trialEpisodeIds(targets)
            continueListening.removeAll(targets)
            val restorable = targets.filterTo(HashSet()) { it !in trial }
            val restoreOrder = before.filterNot { it in targets && it in trial }
            val undo: (suspend () -> Unit)? = if (restorable.isEmpty()) {
                null
            } else {
                suspend {
                    restoreOrder.filter { it in restorable }.forEach { continueListening.append(it) }
                    val last = restoreOrder.lastOrNull { it in restorable }
                    if (restoreOrder.size > 1 && last != null) continueListening.reorder(restoreOrder, last)
                }
            }
            onDone(targets.size, undo)
        }
    }

    fun removeDownloads(items: List<BookWithProgress>) {
        val present = downloaded.value
        act { items.filter { it.book.id in present }.forEach { downloads.remove(it.book.id) } }
    }

    /**
     * Downloads all episodes not yet downloaded (not those from the device); [onHint] as with [download], at most once. Episodes
     * of a trial subscription go into the queue as well, like with the row's download button (review2 J4).
     */
    fun download(items: List<BookWithProgress>, onHint: (Int) -> Unit) {
        val present = downloaded.value
        val missing = items.filter { !it.book.isLocal && it.book.id !in present }.map { it.book.id }
        act { rows.download(missing)?.let(onHint) }
    }

    /** [onHint] receives a text if the download doesn't start right away (offline, waiting for Wi-Fi). */
    fun download(item: BookWithProgress, onHint: (Int) -> Unit) {
        act {
            val hint = if (item.isEpisode) {
                rows.download(item.book.id)
            } else {
                downloadHint(listOf(bookDownloads.download(item.book.id)), book = true)
            }
            hint?.let(onHint)
        }
    }
}

/**
 * What [EpisodeMenu] needs besides the item. [queueHead]: the frontmost episode of the row, "Play next" goes after it; if the
 * episode already is [queueSecond], nothing would change. [loadedBookId]: a book from ARD Sounds can't be removed while loaded.
 */
data class EpisodeMenuFacts(
    val queued: Set<Long>,
    val downloaded: Set<Long>,
    val downloadedEpisodes: Set<Long>,
    val queueHead: Long?,
    val queueSecond: Long?,
    val trialFeeds: Set<String>,
    val loadedBookId: Long?,
)
