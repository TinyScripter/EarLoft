package keinabersmart.kleinaberlaut.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.DownloadOverview
import keinabersmart.kleinaberlaut.core.model.DownloadedAlbum
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsUiState(
    val overview: DownloadOverview = DownloadOverview.EMPTY,
    val albums: List<DownloadedAlbum> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    val state: StateFlow<DownloadsUiState> = combine(
        downloadRepository.overview(),
        downloadRepository.downloadedAlbums(),
    ) { overview, albums ->
        DownloadsUiState(overview = overview, albums = albums, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun retryFailed() {
        viewModelScope.launch { downloadRepository.retryFailed() }
    }

    fun removeAlbum(albumId: Long) {
        viewModelScope.launch { downloadRepository.removeAlbum(albumId) }
    }

    fun removeAll() {
        viewModelScope.launch { downloadRepository.removeAll() }
    }
}
