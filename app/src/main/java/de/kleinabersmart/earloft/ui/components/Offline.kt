package de.kleinabersmart.earloft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.model.OfflineMark
import de.kleinabersmart.earloft.core.model.SongOfflineLookup
import de.kleinabersmart.earloft.core.model.SongOfflineStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Offline state and download actions for every song row of the app, plus the album marks. Row and tile
 * read the state via [statusOf] or [albumMarkOf] and only recompose when their own state changes –
 * not on every progress update of other songs.
 */
@Stable
class OfflineController(
    private val lookup: State<SongOfflineLookup>,
    private val albumMarks: State<Map<Long, OfflineMark>>,
    val download: (songId: Long) -> Unit,
    val removeDownload: (songId: Long) -> Unit,
) {
    @Composable
    fun statusOf(songId: Long): SongOfflineStatus {
        val status = remember(songId) { derivedStateOf { lookup.value[songId] } }
        return status.value
    }

    @Composable
    fun albumMarkOf(albumId: Long): OfflineMark? {
        val mark = remember(albumId) { derivedStateOf { albumMarks.value[albumId] } }
        return mark.value
    }
}

/** `null` (preview, tests): rows show no download state. */
val LocalOfflineController = staticCompositionLocalOf<OfflineController?> { null }

@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    val lookup: StateFlow<SongOfflineLookup> = downloadRepository.songStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongOfflineLookup.EMPTY)

    val albumMarks: StateFlow<Map<Long, OfflineMark>> = downloadRepository.albumMarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun download(songId: Long) {
        viewModelScope.launch { downloadRepository.downloadSongs(listOf(songId)) }
    }

    fun removeDownload(songId: Long) {
        viewModelScope.launch { downloadRepository.removeSongs(listOf(songId)) }
    }
}

@Composable
fun rememberOfflineController(viewModel: OfflineViewModel = hiltViewModel()): OfflineController {
    val lookup = viewModel.lookup.collectAsStateWithLifecycle()
    val albumMarks = viewModel.albumMarks.collectAsStateWithLifecycle()
    return remember(viewModel) { OfflineController(lookup, albumMarks, viewModel::download, viewModel::removeDownload) }
}
