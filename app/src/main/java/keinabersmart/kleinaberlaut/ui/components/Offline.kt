package keinabersmart.kleinaberlaut.ui.components

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
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.SongOfflineLookup
import keinabersmart.kleinaberlaut.core.model.SongOfflineStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Offline-Zustand und Download-Aktionen für jede Song-Zeile der App. Die Zeile liest den Zustand über
 * [statusOf] und recomposed nur, wenn sich ihr eigener Zustand ändert – nicht bei jedem Fortschritt anderer Songs.
 */
@Stable
class OfflineController(
    private val lookup: State<SongOfflineLookup>,
    val download: (songId: Long) -> Unit,
    val removeDownload: (songId: Long) -> Unit,
) {
    @Composable
    fun statusOf(songId: Long): SongOfflineStatus {
        val status = remember(songId) { derivedStateOf { lookup.value[songId] } }
        return status.value
    }
}

/** `null` (Vorschau, Tests): Zeilen zeigen keinen Download-Zustand. */
val LocalOfflineController = staticCompositionLocalOf<OfflineController?> { null }

@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
) : ViewModel() {
    val lookup: StateFlow<SongOfflineLookup> = downloadRepository.songStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongOfflineLookup.EMPTY)

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
    return remember(viewModel) { OfflineController(lookup, viewModel::download, viewModel::removeDownload) }
}
