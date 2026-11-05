package de.kleinabersmart.kleinaberlaut.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.core.data.podcast.FeedDownload
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import de.kleinabersmart.kleinaberlaut.core.data.spoken.BookDownload
import de.kleinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import de.kleinabersmart.kleinaberlaut.core.download.BookDownloadProgress
import de.kleinabersmart.kleinaberlaut.core.download.BookDownloads
import de.kleinabersmart.kleinaberlaut.core.download.BookTransfer
import de.kleinabersmart.kleinaberlaut.core.download.DownloadRepository
import de.kleinabersmart.kleinaberlaut.core.download.EpisodeDownloads
import de.kleinabersmart.kleinaberlaut.core.model.Book
import de.kleinabersmart.kleinaberlaut.core.model.DownloadOverview
import de.kleinabersmart.kleinaberlaut.core.model.DownloadedAlbum
import de.kleinabersmart.kleinaberlaut.core.model.TrackDownload
import de.kleinabersmart.kleinaberlaut.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ein Buch, das geladen ist, lädt oder wartet (oder dessen Download fehlschlug). */
data class DownloadedBook(val book: Book, val download: BookDownload)

data class DownloadsUiState(
    val overview: DownloadOverview = DownloadOverview.EMPTY,
    val albums: List<DownloadedAlbum> = emptyList(),
    val books: List<DownloadedBook> = emptyList(),
    val podcasts: List<FeedDownload> = emptyList(),
    val isLoading: Boolean = true,
    val isFailed: Boolean = false,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val books: BookRepository,
    private val bookDownloads: BookDownloads,
    private val podcastRepository: PodcastRepository,
    private val episodeDownloads: EpisodeDownloads,
    bookProgress: BookDownloadProgress,
) : ViewModel() {

    val state: StateFlow<DownloadsUiState> = combine(
        downloadRepository.overview(),
        downloadRepository.downloadedAlbums(),
        books.books(),
        books.downloads(),
        podcastRepository.feedDownloads(),
    ) { overview, albums, allBooks, downloads, podcasts ->
        val shown = setOf(TrackDownload.DONE, TrackDownload.QUEUED, TrackDownload.RUNNING, TrackDownload.FAILED)
        val downloadedBooks = allBooks.mapNotNull { item ->
            downloads[item.book.id]?.takeIf { it.state in shown }?.let { DownloadedBook(item.book, it) }
        }
        // Die Kopfzeile „belegt“ zählt die Musik; die geladenen Bücher und Folgen kommen dazu.
        val used = overview.usedBytes + downloadedBooks.sumOf { it.download.bytes } + podcasts.sumOf { it.bytes }
        DownloadsUiState(
            overview = overview.copy(usedBytes = used),
            albums = albums,
            books = downloadedBooks,
            podcasts = podcasts,
            isLoading = false,
        )
    }
        .orOnError(DownloadsUiState(isLoading = false, isFailed = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    /** Bytes der gerade ladenden Bücher. */
    val transfers: StateFlow<Map<Long, BookTransfer>> = bookProgress.transfers

    fun retryFailed() {
        viewModelScope.launch { downloadRepository.retryFailed() }
    }

    fun removeAlbum(albumId: Long) {
        viewModelScope.launch { downloadRepository.removeAlbum(albumId) }
    }

    fun removeBook(bookId: Long) {
        viewModelScope.launch { bookDownloads.remove(bookId) }
    }

    fun removeFeed(feedId: String) {
        viewModelScope.launch { episodeDownloads.removeFeed(feedId) }
    }

    /** Alle Musik-Downloads (Alben, Playlists, Favoriten). */
    fun removeAllMusic() {
        viewModelScope.launch { downloadRepository.removeAll() }
    }

    fun removeAllBooks() {
        viewModelScope.launch {
            books.downloads().first().filterValues { it.state != TrackDownload.NONE }.keys.forEach { bookDownloads.remove(it) }
        }
    }

    fun removeAllPodcasts() {
        viewModelScope.launch { episodeDownloads.removeAll() }
    }

    fun removeAll() {
        removeAllMusic()
        removeAllBooks()
        removeAllPodcasts()
    }
}
