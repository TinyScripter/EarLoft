package keinabersmart.kleinaberlaut.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.spoken.BookDownload
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.download.BookDownloadProgress
import keinabersmart.kleinaberlaut.core.download.BookDownloads
import keinabersmart.kleinaberlaut.core.download.BookTransfer
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.DownloadOverview
import keinabersmart.kleinaberlaut.core.model.DownloadedAlbum
import keinabersmart.kleinaberlaut.core.model.TrackDownload
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
    val isLoading: Boolean = true,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val books: BookRepository,
    private val bookDownloads: BookDownloads,
    bookProgress: BookDownloadProgress,
) : ViewModel() {

    val state: StateFlow<DownloadsUiState> = combine(
        downloadRepository.overview(),
        downloadRepository.downloadedAlbums(),
        books.books(),
        books.downloads(),
    ) { overview, albums, allBooks, downloads ->
        val shown = setOf(TrackDownload.DONE, TrackDownload.QUEUED, TrackDownload.RUNNING, TrackDownload.FAILED)
        val downloadedBooks = allBooks.mapNotNull { item ->
            downloads[item.book.id]?.takeIf { it.state in shown }?.let { DownloadedBook(item.book, it) }
        }
        // Die Kopfzeile „belegt“ zählt die Musik; die geladenen Bücher kommen dazu.
        val withBooks = overview.copy(usedBytes = overview.usedBytes + downloadedBooks.sumOf { it.download.bytes })
        DownloadsUiState(overview = withBooks, albums = albums, books = downloadedBooks, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

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

    fun removeAll() {
        viewModelScope.launch {
            downloadRepository.removeAll()
            books.downloads().first().filterValues { it.state != TrackDownload.NONE }.keys.forEach { bookDownloads.remove(it) }
        }
    }
}
