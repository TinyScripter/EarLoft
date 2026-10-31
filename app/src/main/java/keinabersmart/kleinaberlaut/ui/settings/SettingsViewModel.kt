package keinabersmart.kleinaberlaut.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.ServerRepository
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkSettings
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastSettings
import keinabersmart.kleinaberlaut.core.data.spoken.AuthorImages
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenSettings
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySettings
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySyncer
import keinabersmart.kleinaberlaut.core.data.sync.SyncSettings
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.download.DownloadSettings
import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.core.model.ServerInfo
import keinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
import keinabersmart.kleinaberlaut.ui.theme.ThemeMode
import keinabersmart.kleinaberlaut.ui.theme.ThemeSettings
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
    /** Von diesem Server wird alles automatisch heruntergeladen. */
    val autoDownload: Boolean = false,
)

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useLocalMedia: Boolean = true,
    val servers: List<ServerItem> = emptyList(),
    val isSyncing: Boolean = false,
    val downloadWifiOnly: Boolean = true,
    val autoDownloadFavorites: Boolean = false,
    val scrobble: Boolean = true,
    val replayGainMode: ReplayGainMode = ReplayGainMode.AUTO,
    val preAmpDb: Float = 0f,
    val artistImages: Boolean = false,
    val albumCovers: Boolean = false,
    val authorImages: Boolean = false,
    val searchAll: Boolean = true,
    val podcastRefreshHours: Int = PodcastSettings.DEFAULT_REFRESH_HOURS,
    val podcastMobileData: Boolean = false,
    val podcastDeleteHeardDays: Int = PodcastSettings.DEFAULT_DELETE_HEARD_DAYS,
    val podcastAutoDownloadCount: Int = PodcastSettings.DEFAULT_AUTO_DOWNLOAD_COUNT,
    val podcastAutoPlayNext: Boolean = true,
)

private data class GeneralSettings(
    val themeMode: ThemeMode,
    val useLocalMedia: Boolean,
    val artistImages: Boolean,
    val albumCovers: Boolean,
    val searchAll: Boolean,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val refresher: LibraryRefresher,
    private val downloadSettings: DownloadSettings,
    private val downloadRepository: DownloadRepository,
    private val syncSettings: SyncSettings,
    private val replayGainSettings: ReplayGainSettings,
    private val themeSettings: ThemeSettings,
    private val librarySettings: LibrarySettings,
    private val spokenSettings: SpokenSettings,
    private val podcastSettings: PodcastSettings,
    private val artworkSettings: ArtworkSettings,
    private val artworkRepository: ArtworkRepository,
    private val authorImages: AuthorImages,
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
        downloadSettings.autoDownloadServers,
    ) { servers, failures, unusable, autoServers ->
        servers.map {
            ServerItem(
                server = it,
                error = failures[it.sourceId]?.let { e -> e.message ?: e.javaClass.simpleName },
                passwordLost = it.sourceId in unusable,
                autoDownload = it.sourceId in autoServers,
            )
        }
    }

    private val generalSettings = combine(
        themeSettings.mode,
        librarySettings.useLocalMedia,
        artworkSettings.artistImages,
        artworkSettings.albumCovers,
        spokenSettings.searchAll,
    ) { theme, local, artists, albums, searchAll ->
        GeneralSettings(theme, local, artists, albums, searchAll)
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
    }.combine(generalSettings) { state, general ->
        state.copy(
            themeMode = general.themeMode,
            useLocalMedia = general.useLocalMedia,
            artistImages = general.artistImages,
            albumCovers = general.albumCovers,
            searchAll = general.searchAll,
        )
    }.combine(artworkSettings.authorImages) { state, authors ->
        state.copy(authorImages = authors)
    }.combine(
        combine(podcastSettings.refreshHours, podcastSettings.useMobileData, podcastSettings.deleteHeardAfterDays, ::Triple),
    ) { state, (hours, mobile, deleteHeardDays) ->
        state.copy(podcastRefreshHours = hours, podcastMobileData = mobile, podcastDeleteHeardDays = deleteHeardDays)
    }.combine(podcastSettings.autoDownloadCount) { state, count ->
        state.copy(podcastAutoDownloadCount = count)
    }.combine(podcastSettings.autoPlayNext) { state, autoPlay ->
        state.copy(podcastAutoPlayNext = autoPlay)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Die Planung des periodischen Auftrags folgt der Einstellung (PodcastRefresher). */
    fun setPodcastRefreshHours(hours: Int) {
        viewModelScope.launch { podcastSettings.setRefreshHours(hours) }
    }

    /** Die Aufräum-Automatik (EpisodeDownloads) folgt der Einstellung. */
    fun setPodcastDeleteHeardDays(days: Int) {
        viewModelScope.launch { podcastSettings.setDeleteHeardAfterDays(days) }
    }

    /** Die Download-Automatik (EpisodeDownloads) holt bei einer größeren Zahl sofort Folgen nach. */
    fun setPodcastAutoDownloadCount(count: Int) {
        viewModelScope.launch { podcastSettings.setAutoDownloadCount(count) }
    }

    fun setPodcastAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setAutoPlayNext(enabled) }
    }

    fun setPodcastMobileData(enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setUseMobileData(enabled) }
    }

    fun setSearchAll(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setSearchAll(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeSettings.setMode(mode) }
    }

    /** Einlesen bzw. Entfernen der lokalen Songs übernimmt der [LibraryRefresher]. */
    fun setUseLocalMedia(enabled: Boolean) {
        viewModelScope.launch { librarySettings.setUseLocalMedia(enabled) }
    }

    /** Beim Ausschalten werden die schon geladenen Bilder gelöscht. */
    fun setArtistImages(enabled: Boolean) {
        viewModelScope.launch {
            artworkSettings.setArtistImages(enabled)
            artworkRepository.clearDisabledOnlineImages()
        }
    }

    fun setAlbumCovers(enabled: Boolean) {
        viewModelScope.launch {
            artworkSettings.setAlbumCovers(enabled)
            artworkRepository.clearDisabledOnlineImages()
        }
    }

    /** Beim Ausschalten werden die schon geladenen Autorenbilder aus dem Internet gelöscht. */
    fun setAuthorImages(enabled: Boolean) {
        viewModelScope.launch {
            artworkSettings.setAuthorImages(enabled)
            if (!enabled) authorImages.clearOnlineImages()
        }
    }

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
        viewModelScope.launch {
            serverRepository.removeServer(sourceId)
            downloadSettings.setAutoDownloadServer(sourceId, false)
        }
    }
}
