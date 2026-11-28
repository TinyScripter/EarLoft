package de.kleinabersmart.earloft.ui.components

import android.content.Context
import androidx.compose.material3.SnackbarDuration
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
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.model.OfflineMark
import de.kleinabersmart.earloft.core.model.SongOfflineLookup
import de.kleinabersmart.earloft.core.model.SongOfflineState
import de.kleinabersmart.earloft.core.model.SongOfflineStatus
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val pendingRemovals: State<Set<Long>>,
    private val albumMarks: State<Map<Long, OfflineMark>>,
    val download: (songId: Long) -> Unit,
    val removeDownload: (songId: Long, title: String, downloaded: Boolean) -> Unit,
) {
    @Composable
    fun statusOf(songId: Long): SongOfflineStatus {
        val status = remember(songId) {
            derivedStateOf {
                if (songId in pendingRemovals.value) SongOfflineStatus(SongOfflineState.NONE) else lookup.value[songId]
            }
        }
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
    private val snackbars: Snackbars,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {
    val lookup: StateFlow<SongOfflineLookup> = downloadRepository.songStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongOfflineLookup.EMPTY)

    val albumMarks: StateFlow<Map<Long, OfflineMark>> = downloadRepository.albumMarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private class PendingRemoval(val token: Long, val message: SnackbarMessage)

    private var nextToken = 0L
    private val pending = MutableStateFlow<Map<Long, PendingRemoval>>(emptyMap())

    /** Songs whose completed download is waiting for the "Undo" snackbar to expire before deleting the file (review5 P58). */
    val pendingRemovals: StateFlow<Set<Long>> = pending
        .map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun download(songId: Long) {
        var restored: SnackbarMessage? = null
        pending.update { map ->
            val entry = map[songId] ?: return@update map
            restored = entry.message
            map - songId
        }
        restored?.let {
            // File is still on disk: simply cancel the pending removal instead of re-downloading.
            snackbars.withdraw(it)
            return
        }
        applicationScope.launch { downloadRepository.downloadSongs(listOf(songId)) }
    }

    /**
     * Cancels a waiting/running download right away, or defers deleting a finished song download until the
     * "Undo" snackbar finishes so undoing keeps the local file without re-downloading (review5 P58).
     */
    fun removeDownload(songId: Long, title: String, downloaded: Boolean) {
        if (!downloaded) {
            applicationScope.launch { downloadRepository.removeSongs(listOf(songId)) }
            return
        }
        if (songId in pending.value) return
        val token = nextToken++
        val message = SnackbarMessage(
            text = context.getString(R.string.song_download_removed, title),
            actionLabel = context.getString(R.string.action_undo),
            onAction = { cancelPendingRemoval(songId, token) },
            duration = SnackbarDuration.Long,
            onDismissed = { commitPendingRemoval(songId, token) },
        )
        pending.update { it + (songId to PendingRemoval(token, message)) }
        snackbars.show(message)
    }

    private fun cancelPendingRemoval(songId: Long, token: Long) {
        pending.update { map -> if (map[songId]?.token == token) map - songId else map }
    }

    private fun commitPendingRemoval(songId: Long, token: Long) {
        var commit = false
        pending.update { map ->
            if (map[songId]?.token == token) {
                commit = true
                map - songId
            } else {
                map
            }
        }
        if (commit) applicationScope.launch { downloadRepository.removeSongs(listOf(songId)) }
    }

    override fun onCleared() {
        val remaining = pending.getAndUpdate { emptyMap() }.keys.toList()
        if (remaining.isNotEmpty()) applicationScope.launch { downloadRepository.removeSongs(remaining) }
    }
}

@Composable
fun rememberOfflineController(viewModel: OfflineViewModel = hiltViewModel()): OfflineController {
    val lookup = viewModel.lookup.collectAsStateWithLifecycle()
    val pendingRemovals = viewModel.pendingRemovals.collectAsStateWithLifecycle()
    val albumMarks = viewModel.albumMarks.collectAsStateWithLifecycle()
    return remember(viewModel) { OfflineController(lookup, pendingRemovals, albumMarks, viewModel::download, viewModel::removeDownload) }
}
