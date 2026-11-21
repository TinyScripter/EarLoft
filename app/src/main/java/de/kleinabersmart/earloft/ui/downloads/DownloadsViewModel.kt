package de.kleinabersmart.earloft.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.FeedDownload
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.BookDownload
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.download.BookDownloadProgress
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.BookTransfer
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.download.DownloadSpace
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.DownloadOverview
import de.kleinabersmart.earloft.core.model.DownloadedAlbum
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A book that is downloaded, downloading or waiting (or whose download failed). */
data class DownloadedBook(val book: Book, val download: BookDownload)

data class DownloadsUiState(
    val overview: DownloadOverview = DownloadOverview.EMPTY,
    val albums: List<DownloadedAlbum> = emptyList(),
    val books: List<DownloadedBook> = emptyList(),
    val podcasts: List<FeedDownload> = emptyList(),
    val isLoading: Boolean = true,
    val isFailed: Boolean = false,
    /** Downloads wait because there is not enough storage (review H7). */
    val storageShort: Boolean = false,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val books: BookRepository,
    private val bookDownloads: BookDownloads,
    private val podcastRepository: PodcastRepository,
    private val episodeDownloads: EpisodeDownloads,
    bookProgress: BookDownloadProgress,
    spokenSettings: SpokenSettings,
    space: DownloadSpace,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    /** Deleting finishes in the app scope: "Back" right after "Delete all" must not stop it midway (review D13, H12). */
    private fun act(block: suspend CoroutineScope.() -> Unit) {
        applicationScope.launch(Dispatchers.Main.immediate, block = block)
    }

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
        // The "used" header line counts the music; the downloaded books and episodes are added.
        val used = overview.usedBytes + downloadedBooks.sumOf { it.download.bytes } + podcasts.sumOf { it.bytes }
        DownloadsUiState(
            overview = overview.copy(usedBytes = used),
            albums = albums,
            books = downloadedBooks,
            podcasts = podcasts,
            isLoading = false,
        )
    }.combine(spokenSettings.areas) { state, areas ->
        // Hidden areas (General → Areas) aren't shown in the overview; "used" still counts everything.
        state.copy(
            albums = state.albums.takeIf { SpokenMode.MUSIC in areas }.orEmpty(),
            books = state.books.takeIf { SpokenMode.BOOKS in areas }.orEmpty(),
            podcasts = state.podcasts.takeIf { SpokenMode.PODCASTS in areas }.orEmpty(),
        )
    }.combine(space.isShort) { state, short -> state.copy(storageShort = short) }
        .orOnError(DownloadsUiState(isLoading = false, isFailed = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    /** Bytes of the books currently downloading. */
    val transfers: StateFlow<Map<Long, BookTransfer>> = bookProgress.transfers

    fun retryFailed() {
        act { downloadRepository.retryFailed() }
    }

    fun removeAlbum(albumId: Long) {
        act { downloadRepository.removeAlbum(albumId) }
    }

    fun removeBook(bookId: Long) {
        act { bookDownloads.remove(bookId) }
    }

    fun removeFeed(feedId: String) {
        act { episodeDownloads.removeFeed(feedId) }
    }

    /** All music downloads (albums, playlists, favorites). */
    fun removeAllMusic() {
        act { downloadRepository.removeAll() }
    }

    fun removeAllBooks() {
        act {
            books.downloads().first().filterValues { it.state != TrackDownload.NONE }.keys.forEach { bookDownloads.remove(it) }
        }
    }

    fun removeAllPodcasts() {
        act { episodeDownloads.removeAll() }
    }

    fun removeAll() {
        removeAllMusic()
        removeAllBooks()
        removeAllPodcasts()
    }
}
