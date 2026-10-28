package keinabersmart.kleinaberlaut.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.ServerRepository
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySyncer
import keinabersmart.kleinaberlaut.core.data.sync.SyncSettings
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.download.DownloadSettings
import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.core.model.ServerInfo
import keinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
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
    /** Das gespeicherte Passwort ist nicht mehr lesbar; der Nutzer muss es beim Bearbeiten neu eingeben. */
    val passwordLost: Boolean = false,
)

data class SettingsUiState(
    val servers: List<ServerItem> = emptyList(),
    val isSyncing: Boolean = false,
    val downloadWifiOnly: Boolean = true,
    val autoDownloadFavorites: Boolean = false,
    val scrobble: Boolean = true,
    val replayGainMode: ReplayGainMode = ReplayGainMode.AUTO,
    val preAmpDb: Float = 0f,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val refresher: LibraryRefresher,
    private val downloadSettings: DownloadSettings,
    private val downloadRepository: DownloadRepository,
    private val syncSettings: SyncSettings,
    private val replayGainSettings: ReplayGainSettings,
    private val registry: DataSourceRegistry,
    syncer: LibrarySyncer,
) : ViewModel() {

    init {
        // Die Server werden sonst erst beim ersten Zugriff geladen; ohne das fehlte der Hinweis auf verlorene Passwörter.
        viewModelScope.launch { registry.servers() }
    }

    private val serverItems = combine(
        serverRepository.servers(),
        syncer.failures,
        registry.unusableServers,
    ) { servers, failures, unusable ->
        servers.map {
            ServerItem(
                server = it,
                error = failures[it.sourceId]?.let { e -> e.message ?: e.javaClass.simpleName },
                passwordLost = it.sourceId in unusable,
            )
        }
    }

    val state: StateFlow<SettingsUiState> = combine(
        serverItems,
        syncer.activeSyncs,
        combine(downloadSettings.wifiOnly, downloadSettings.autoDownloadFavorites, ::Pair),
        syncSettings.scrobble,
    ) { servers, syncs, (wifiOnly, autoFavorites), scrobble ->
        SettingsUiState(
            servers = servers,
            isSyncing = syncs > 0,
            downloadWifiOnly = wifiOnly,
            autoDownloadFavorites = autoFavorites,
            scrobble = scrobble,
        )
    }.combine(combine(replayGainSettings.mode, replayGainSettings.preAmpDb, ::Pair)) { state, (mode, preAmpDb) ->
        state.copy(replayGainMode = mode, preAmpDb = preAmpDb)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun syncNow(sourceId: String) = serverRepository.syncNow(sourceId)

    fun refreshAll() = refresher.refreshAll()

    fun setDownloadWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            downloadSettings.setWifiOnly(wifiOnly)
            downloadRepository.rescheduleAfterSettingsChange()
        }
    }

    /** Die Downloads selbst stößt [keinabersmart.kleinaberlaut.core.download.AutoDownloads] an. */
    fun setAutoDownloadFavorites(enabled: Boolean) {
        viewModelScope.launch { downloadSettings.setAutoDownloadFavorites(enabled) }
    }

    fun setScrobble(enabled: Boolean) {
        viewModelScope.launch { syncSettings.setScrobble(enabled) }
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        viewModelScope.launch { replayGainSettings.setMode(mode) }
    }

    fun setPreAmpDb(preAmpDb: Float) {
        viewModelScope.launch { replayGainSettings.setPreAmpDb(preAmpDb) }
    }

    fun remove(sourceId: String) {
        viewModelScope.launch { serverRepository.removeServer(sourceId) }
    }
}
