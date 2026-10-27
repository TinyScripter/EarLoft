package keinabersmart.kleinaberlaut.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.ServerRepository
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySyncer
import keinabersmart.kleinaberlaut.core.data.sync.SyncSettings
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.download.DownloadSettings
import keinabersmart.kleinaberlaut.core.model.ServerInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerItem(
    val server: ServerInfo,
    /** Meldung des letzten fehlgeschlagenen Abgleichs. */
    val error: String?,
)

data class SettingsUiState(
    val servers: List<ServerItem> = emptyList(),
    val isSyncing: Boolean = false,
    val downloadWifiOnly: Boolean = true,
    val scrobble: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val refresher: LibraryRefresher,
    private val downloadSettings: DownloadSettings,
    private val downloadRepository: DownloadRepository,
    private val syncSettings: SyncSettings,
    syncer: LibrarySyncer,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        serverRepository.servers(),
        syncer.failures,
        syncer.activeSyncs,
        downloadSettings.wifiOnly,
        syncSettings.scrobble,
    ) { servers, failures, syncs, wifiOnly, scrobble ->
        SettingsUiState(
            servers = servers.map { ServerItem(it, failures[it.sourceId]?.let { e -> e.message ?: e.javaClass.simpleName }) },
            isSyncing = syncs > 0,
            downloadWifiOnly = wifiOnly,
            scrobble = scrobble,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun syncNow(sourceId: String) = serverRepository.syncNow(sourceId)

    fun refreshAll() = refresher.refreshAll()

    fun setDownloadWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            downloadSettings.setWifiOnly(wifiOnly)
            downloadRepository.rescheduleAfterSettingsChange()
        }
    }

    fun setScrobble(enabled: Boolean) {
        viewModelScope.launch { syncSettings.setScrobble(enabled) }
    }

    fun remove(sourceId: String) {
        viewModelScope.launch { serverRepository.removeServer(sourceId) }
    }
}
