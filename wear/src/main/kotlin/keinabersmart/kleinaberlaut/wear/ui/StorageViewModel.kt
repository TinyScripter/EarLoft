package keinabersmart.kleinaberlaut.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.DownloadOverview
import keinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto
import keinabersmart.kleinaberlaut.wear.download.WatchState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUiState(
    val overview: DownloadOverview? = null,
    /** Feste Obergrenze der Downloads (Einstellung am Handy), sonst `null`. */
    val limitBytes: Long? = null,
    val missingForStorage: Int = 0,
    val lastError: String? = null,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val downloads: DownloadRepository,
    state: WatchState,
) : ViewModel() {

    val state: StateFlow<StorageUiState> = combine(
        downloads.overview(),
        state.settings,
        state.missingForStorage,
        state.lastError,
    ) { overview, settings: WearSettingsDto, missing, error ->
        StorageUiState(overview, settings.storageLimitBytes, missing, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageUiState())

    fun retryFailed() {
        viewModelScope.launch { downloads.retryFailed() }
    }
}
