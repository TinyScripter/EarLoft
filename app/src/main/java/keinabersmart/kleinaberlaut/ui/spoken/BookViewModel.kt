package keinabersmart.kleinaberlaut.ui.spoken

import keinabersmart.kleinaberlaut.core.model.BookKind
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.BookChapter
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.data.spoken.BookDownload
import keinabersmart.kleinaberlaut.core.download.BookDownloadProgress
import keinabersmart.kleinaberlaut.core.download.BookDownloads
import keinabersmart.kleinaberlaut.core.download.BookTransfer
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.download.DownloadStart
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloadProgress
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = BookViewModel.Factory::class)
class BookViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    books: BookRepository,
    podcasts: PodcastRepository,
    private val launcher: BookLauncher,
    private val downloads: BookDownloads,
    private val episodeDownloads: EpisodeDownloads,
    downloadProgress: BookDownloadProgress,
    episodeProgress: EpisodeDownloadProgress,
) : ViewModel() {

    val book: StateFlow<BookWithProgress?> = books.book(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play() = launcher.play(bookId)

    fun playFrom(positionMs: Long) = launcher.play(bookId, positionMs)

    @AssistedFactory
    interface Factory {
        fun create(bookId: Long): BookViewModel
    }
}
