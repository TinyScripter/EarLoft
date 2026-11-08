package de.kleinabersmart.tinyamp.ui.spoken

import de.kleinabersmart.tinyamp.core.model.BookKind
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.spoken.BookRepository
import de.kleinabersmart.tinyamp.core.model.BookChapter
import de.kleinabersmart.tinyamp.core.model.BookWithProgress
import de.kleinabersmart.tinyamp.core.data.spoken.BookDownload
import de.kleinabersmart.tinyamp.core.download.BookDownloadProgress
import de.kleinabersmart.tinyamp.core.download.BookDownloads
import de.kleinabersmart.tinyamp.core.download.BookTransfer
import de.kleinabersmart.tinyamp.core.data.podcast.ContinueListening
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastRepository
import de.kleinabersmart.tinyamp.core.data.podcast.TrialPodcastPages
import de.kleinabersmart.tinyamp.core.download.DownloadStart
import de.kleinabersmart.tinyamp.core.download.EpisodeDownloadProgress
import de.kleinabersmart.tinyamp.core.download.EpisodeDownloads
import de.kleinabersmart.tinyamp.ui.orOnError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import de.kleinabersmart.tinyamp.core.model.TrackDownload
import de.kleinabersmart.tinyamp.core.playback.PlaybackConnection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Der Podcast der Folge, wenn er nur ein Probeabo aus der Vorschau ist (Konzept 12.10); sonst `null`. Solange die
     * Seite offen ist, bleibt das Probeabo bestehen, auch wenn die Folge die Warteschlange verlässt.
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

    /** Download-Stand des Buchs bzw. der Folge (Folgen laden einzeln, ihre Größe kennt hier niemand: `bytes` ist 0). */
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

    /** Anteil der ladenden Folge (0..1, [EpisodeDownloadProgress.UNKNOWN] ohne bekannte Größe); `null`, solange sie nicht lädt. */
    val episodeFraction: StateFlow<Float?> = episodeProgress.fractions.map { it[bookId] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Bytes des ladenden Buchs; `null`, solange es nicht lädt. */
    val transfer: StateFlow<BookTransfer?> = downloadProgress.transfers.map { it[bookId] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun startDownload() {
        viewModelScope.launch {
            // Probeabo: Was geladen wird, kommt in die Warteschlange und bleibt nur, solange es dort steht.
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

    /** Bricht einen laufenden Download ab oder löscht die geladenen Dateien. */
    fun removeDownload() {
        viewModelScope.launch { if (isEpisode) episodeDownloads.remove(bookId) else downloads.remove(bookId) }
    }

    val chapters: StateFlow<List<BookChapter>> = books.chapters(bookId)
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Kanal statt SharedFlow: Ein Ereignis während einer Drehung (kein Sammler) bliebe sonst verloren.
    private val _openPlayer = Channel<Unit>(Channel.CONFLATED)

    /** Die Wiedergabe-Ansicht soll aufgehen: [play] hat das Buch gestartet oder fortgesetzt. */
    val openPlayer: Flow<Unit> = _openPlayer.receiveAsFlow()

    /**
     * „Abspielen“/„Weiterhören“: Ist das Buch schon geladen, geht es dort weiter, sonst wird es gestartet (ein zu Ende
     * gehörtes von vorn); danach geht die Wiedergabe-Ansicht auf.
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
