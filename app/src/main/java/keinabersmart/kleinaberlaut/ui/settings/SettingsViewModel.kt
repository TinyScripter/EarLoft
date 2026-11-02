package keinabersmart.kleinaberlaut.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.podcast.DatabaseReader
import keinabersmart.kleinaberlaut.core.data.podcast.DatabaseImporter
import keinabersmart.kleinaberlaut.core.data.podcast.DatabaseImportResult
import keinabersmart.kleinaberlaut.core.data.podcast.OpmlImportResult
import keinabersmart.kleinaberlaut.core.network.podcast.Opml
import keinabersmart.kleinaberlaut.core.data.repository.ServerRepository
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkSettings
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastSettings
import keinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import keinabersmart.kleinaberlaut.core.data.spoken.AuthorImages
import keinabersmart.kleinaberlaut.core.data.spoken.ResumeRule
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    val alwaysShowNavigationBar: Boolean = true,
    val useLocalMedia: Boolean = true,
    val useLocalBooks: Boolean = true,
    val useLocalPodcasts: Boolean = true,
    val servers: List<ServerItem> = emptyList(),
    val audiobookServerCount: Int = 0,
    val podcastFeedCount: Int = 0,
    val syncIntervalDays: Int = LibrarySettings.DEFAULT_SYNC_DAYS,
    val isSyncing: Boolean = false,
    val downloadWifiOnly: Boolean = true,
    val autoDownloadFavorites: Boolean = false,
    val scrobble: Boolean = true,
    val replayGainMode: ReplayGainMode = ReplayGainMode.AUTO,
    val preAmpDb: Float = 0f,
    val artistImages: Boolean = false,
    val albumCovers: Boolean = false,
    val authorImages: Boolean = false,
    val podcastRefreshHours: Int = PodcastSettings.DEFAULT_REFRESH_HOURS,
    val podcastMobileData: Boolean = false,
    val podcastDeleteHeardDays: Int = PodcastSettings.DEFAULT_DELETE_HEARD_DAYS,
    val podcastAutoDownloadCount: Int = PodcastSettings.DEFAULT_AUTO_DOWNLOAD_COUNT,
    val podcastAutoPlayNext: Boolean = true,
    val bookResume: ResumeRule = ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S),
    val podcastResume: ResumeRule = ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S),
    val restartChapter: Boolean = false,
)

private data class GeneralSettings(
    val themeMode: ThemeMode,
    val useLocalMedia: Boolean,
    val artistImages: Boolean,
    val albumCovers: Boolean,
)

/** Ausgang eines OPML-Im- oder -Exports. */
sealed interface TransferMessage {
    data class Imported(val result: OpmlImportResult) : TransferMessage
    data object ImportFailed : TransferMessage
    data object Exported : TransferMessage
    data object ExportFailed : TransferMessage
    data object DatabaseImportStarted : TransferMessage
    data class DatabaseImported(val result: DatabaseImportResult) : TransferMessage
    data object DatabaseImportFailed : TransferMessage
}

private const val TAG = "SettingsViewModel"

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
    private val spokenRefresher: SpokenRefresher,
    private val podcastRefresher: PodcastRefresher,
    private val podcastRepository: PodcastRepository,
    private val databaseImporter: DatabaseImporter,
    @param:ApplicationContext private val context: Context,
    audiobookServers: AudiobookshelfServers,
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
    ) { theme, local, artists, albums ->
        GeneralSettings(theme, local, artists, albums)
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
        )
    }.combine(artworkSettings.authorImages) { state, authors ->
        state.copy(authorImages = authors)
    }.combine(themeSettings.alwaysShowNavigationBar) { state, always ->
        state.copy(alwaysShowNavigationBar = always)
    }.combine(combine(spokenSettings.useLocalBooks, spokenSettings.useLocalPodcasts, ::Pair)) { state, (books, podcasts) ->
        state.copy(useLocalBooks = books, useLocalPodcasts = podcasts)
    }.combine(audiobookServers.servers) { state, list ->
        state.copy(audiobookServerCount = list.size)
    }.combine(podcastRepository.feeds) { state, feeds ->
        state.copy(podcastFeedCount = feeds.size)
    }.combine(librarySettings.syncIntervalDays) { state, days ->
        state.copy(syncIntervalDays = days)
    }.combine(
        combine(podcastSettings.refreshHours, podcastSettings.useMobileData, podcastSettings.deleteHeardAfterDays, ::Triple),
    ) { state, (hours, mobile, deleteHeardDays) ->
        state.copy(podcastRefreshHours = hours, podcastMobileData = mobile, podcastDeleteHeardDays = deleteHeardDays)
    }.combine(podcastSettings.autoDownloadCount) { state, count ->
        state.copy(podcastAutoDownloadCount = count)
    }.combine(podcastSettings.autoPlayNext) { state, autoPlay ->
        state.copy(podcastAutoPlayNext = autoPlay)
    }.combine(combine(spokenSettings.bookResume, spokenSettings.podcastResume, spokenSettings.restartChapter, ::Triple)) { state, (book, podcast, chapter) ->
        state.copy(bookResume = book, podcastResume = podcast, restartChapter = chapter)
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

    fun setBookResume(rule: ResumeRule) {
        viewModelScope.launch { spokenSettings.setBookResume(rule) }
    }

    fun setPodcastResume(rule: ResumeRule) {
        viewModelScope.launch { spokenSettings.setPodcastResume(rule) }
    }

    fun setRestartChapter(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setRestartChapter(enabled) }
    }

    fun setPodcastMobileData(enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setUseMobileData(enabled) }
    }

    /** Die Planung des periodischen Auftrags folgt der Einstellung ([keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher]). */
    fun setSyncIntervalDays(days: Int) {
        viewModelScope.launch { librarySettings.setSyncIntervalDays(days) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeSettings.setMode(mode) }
    }

    fun setAlwaysShowNavigationBar(enabled: Boolean) {
        viewModelScope.launch { themeSettings.setAlwaysShowNavigationBar(enabled) }
    }

    /** Einlesen bzw. Entfernen der lokalen Songs übernimmt der [LibraryRefresher]. */
    fun setUseLocalMedia(enabled: Boolean) {
        viewModelScope.launch { librarySettings.setUseLocalMedia(enabled) }
    }

    /** Einlesen bzw. Entfernen der Bücher vom Gerät übernimmt der [SpokenRefresher]. */
    fun setUseLocalBooks(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setUseLocalBooks(enabled) }
    }

    /** Einlesen bzw. Entfernen der Podcast-Dateien vom Gerät übernimmt der [SpokenRefresher]. */
    fun setUseLocalPodcasts(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setUseLocalPodcasts(enabled) }
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

    /** Lokale Musik und alle Navidrome-Server. */
    fun refreshMusic() = refresher.refreshAll()

    fun refreshAudiobooks() = spokenRefresher.refreshAll()

    fun refreshPodcasts() = podcastRefresher.refreshAll()

    private val _transferMessages = Channel<TransferMessage>(Channel.BUFFERED)

    /** Ergebnis des letzten OPML-Im- oder -Exports, einmalig anzuzeigen. */
    val transferMessages: Flow<TransferMessage> = _transferMessages.receiveAsFlow()

    /** Liest die Abos aus [uri] und übernimmt sie ([replace]: alle anderen Abos entfallen); danach werden die Feeds abgeglichen. */
    fun importOpml(uri: Uri, replace: Boolean) {
        viewModelScope.launch {
            val message = try {
                val feeds = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(Opml::parse) ?: throw IOException("Datei nicht lesbar")
                }
                val result = podcastRepository.importOpml(feeds, replace)
                if (result.added > 0) refreshPodcasts()
                TransferMessage.Imported(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "OPML-Import fehlgeschlagen", e)
                TransferMessage.ImportFailed
            }
            _transferMessages.send(message)
        }
    }

    private var backupRunning = false

    /**
     * Liest die Datenbank-Datei aus [uri]: fehlende aktive Abos, Hörfortschritt und gehörte Zeit (Statistik).
     * Dauert, weil neue Feeds abgeglichen werden; ein zweiter Aufruf in der Zwischenzeit wird ignoriert.
     */
    fun importImportData(uri: Uri) {
        if (backupRunning) return
        backupRunning = true
        viewModelScope.launch {
            val file = File(context.cacheDir, "import.db")
            try {
                _transferMessages.send(TransferMessage.DatabaseImportStarted)
                val backup = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        ?: throw IOException("Datei nicht lesbar")
                    DatabaseReader.read(file)
                }
                _transferMessages.send(TransferMessage.DatabaseImported(databaseImporter.import(backup)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Backup-Import fehlgeschlagen", e)
                _transferMessages.send(TransferMessage.DatabaseImportFailed)
            } finally {
                file.delete()
                backupRunning = false
            }
        }
    }

    /** Schreibt die Abos als OPML nach [uri]. */
    fun exportOpml(uri: Uri, title: String) {
        viewModelScope.launch {
            val message = try {
                val created = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
                val text = podcastRepository.exportOpml(title, created)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Datei nicht beschreibbar")
                }
                TransferMessage.Exported
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "OPML-Export fehlgeschlagen", e)
                TransferMessage.ExportFailed
            }
            _transferMessages.send(message)
        }
    }

    fun refreshAll() {
        refreshMusic()
        refreshAudiobooks()
        refreshPodcasts()
    }

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

    fun setServerEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch { serverRepository.setEnabled(sourceId, enabled) }
    }

    fun remove(sourceId: String) {
        viewModelScope.launch {
            serverRepository.removeServer(sourceId)
            downloadSettings.setAutoDownloadServer(sourceId, false)
        }
    }
}
