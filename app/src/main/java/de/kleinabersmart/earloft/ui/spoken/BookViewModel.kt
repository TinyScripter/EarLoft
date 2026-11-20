package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.core.model.BookKind
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.EpisodePerson
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.data.spoken.BookDownload
import de.kleinabersmart.earloft.core.download.BookDownloadProgress
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.BookTransfer
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.TrialPodcastPages
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.download.EpisodeDownloadProgress
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = BookViewModel.Factory::class)
class BookViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    books: BookRepository,
    podcasts: PodcastRepository,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    private val downloads: BookDownloads,
    private val episodeDownloads: EpisodeDownloads,
    downloadProgress: BookDownloadProgress,
    episodeProgress: EpisodeDownloadProgress,
    private val continueListening: ContinueListening,
    private val trialPages: TrialPodcastPages,
) : ViewModel() {

    val book: StateFlow<BookWithProgress?> = books.book(bookId)
        .onEach { if (it != null) wasLoaded = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Whether the entry no longer exists. Unlike [wasLoaded] this survives a process death: a page restored for a book removed
     * meanwhile shows a hint instead of staying blank (review G56).
     */
    val missing: StateFlow<Boolean> = books.book(bookId).map { it == null }.orOnError(false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The book was already there; if it is now `null`, it was removed (not: still loading). */
    var wasLoaded = false
        private set

    /** The page is currently being replaced (after uploading): the book disappearing doesn't close it. */
    fun markLeaving() {
        wasLoaded = false
    }

    /**
     * The episode's podcast, if it is only a trial subscription from the preview (concept 12.10); otherwise `null`. As long as the
     * page is open, the trial subscription persists, even if the episode leaves the queue.
     */
    val trialFeedId: StateFlow<String?> = book
        .map { it?.book?.takeIf { book -> book.kind == BookKind.EPISODE }?.serverId }
        .distinctUntilChanged()
        .flatMapLatest { feedId -> if (feedId == null) flowOf(null) else podcasts.feed(feedId) }
        .map { feed -> feed?.takeIf { !it.subscribed }?.id }
        .distinctUntilChanged()
        .onEach(::keepTrialOpen)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var openTrialFeedId: String? = null

    private fun keepTrialOpen(feedId: String?) {
        if (feedId == null || openTrialFeedId != null) return
        openTrialFeedId = feedId
        trialPages.opened(feedId)
    }

    override fun onCleared() {
        openTrialFeedId?.let(trialPages::closed)
    }

    private val isEpisode: Boolean get() = book.value?.book?.kind == BookKind.EPISODE

    /** Download state of the book or episode (episodes download individually, nobody here knows their size: `bytes` is 0). */
    val download: StateFlow<BookDownload> = book
        .map { it?.book?.takeIf { book -> book.kind == BookKind.EPISODE }?.serverId }
        .distinctUntilChanged()
        .flatMapLatest { feedId ->
            if (feedId == null) {
                books.download(bookId)
            } else {
                podcasts.downloadStates(feedId).map { BookDownload(it[bookId] ?: TrackDownload.NONE, 0) }.distinctUntilChanged()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDownload(TrackDownload.NONE, 0))

    /** Fraction of the downloading episode (0..1, [EpisodeDownloadProgress.UNKNOWN] without known size); `null` while it isn't downloading. */
    val episodeFraction: StateFlow<Float?> = episodeProgress.fractions.map { it[bookId] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Bytes of the downloading book; `null` while it isn't downloading. */
    val transfer: StateFlow<BookTransfer?> = downloadProgress.transfers.map { it[bookId] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun startDownload() {
        viewModelScope.launch {
            // Trial subscription: whatever is downloaded goes into the queue and stays only as long as it is there.
            if (trialFeedId.value != null) continueListening.append(bookId)
            val start = if (isEpisode) episodeDownloads.download(bookId) else downloads.download(bookId)
            when (start) {
                DownloadStart.OFFLINE -> _messages.emit(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI ->
                    _messages.emit(if (isEpisode) R.string.podcast_download_waiting_wifi else R.string.book_download_waiting_wifi)
                DownloadStart.STARTED -> Unit
            }
        }
    }

    /** Cancels a running download or deletes the downloaded files. */
    fun removeDownload() {
        viewModelScope.launch { if (isEpisode) episodeDownloads.remove(bookId) else downloads.remove(bookId) }
    }

    val chapters: StateFlow<List<BookChapter>> = books.chapters(bookId)
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Contributors of an episode from the feed (`podcast:person`); empty for books. */
    val persons: StateFlow<List<EpisodePerson>> = books.persons(bookId)
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Channel instead of SharedFlow: an event during a rotation (no collector) would otherwise be lost.
    private val _openPlayer = Channel<Unit>(Channel.CONFLATED)

    /** The now-playing view should open: [play] has started or resumed the book. */
    val openPlayer: Flow<Unit> = _openPlayer.receiveAsFlow()

    init {
        // If the mobile-data question came before the start, the book starts only after "Play".
        viewModelScope.launch { launcher.confirmedStarts.filter { it == bookId }.collect { _openPlayer.send(Unit) } }
    }

    /**
     * "Play"/"Continue listening": if the book is already loaded, it continues there, otherwise it is started (a finished
     * one from the start); afterwards the now-playing view opens.
     */
    fun play() {
        val state = playback.state.value
        if (state.spoken?.bookId == bookId && book.value?.progress?.finished != true) {
            if (!state.isPlaying) playback.togglePlayPause()
            _openPlayer.trySend(Unit)
        } else {
            viewModelScope.launch { if (launcher.playAndAwait(bookId)) _openPlayer.send(Unit) }
        }
    }

    fun playFrom(positionMs: Long) = launcher.play(bookId, positionMs)

    @AssistedFactory
    interface Factory {
        fun create(bookId: Long): BookViewModel
    }
}
