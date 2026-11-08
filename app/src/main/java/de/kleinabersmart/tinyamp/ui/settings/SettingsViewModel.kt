package de.kleinabersmart.tinyamp.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseReader
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseExportResult
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseImporter
import de.kleinabersmart.tinyamp.core.data.podcast.DatabaseImportResult
import de.kleinabersmart.tinyamp.core.data.podcast.OpmlImportResult
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastDirectorySearch
import de.kleinabersmart.tinyamp.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.tinyamp.ui.spoken.OpmlImporter
import de.kleinabersmart.tinyamp.core.data.repository.ServerRepository
import de.kleinabersmart.tinyamp.core.data.source.DataSourceRegistry
import de.kleinabersmart.tinyamp.core.data.sync.LibraryRefresher
import de.kleinabersmart.tinyamp.core.data.repository.ArtworkRepository
import de.kleinabersmart.tinyamp.core.data.repository.ArtworkSettings
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastRefresher
import de.kleinabersmart.tinyamp.core.data.stats.StatsRepository
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastRepository
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastSettings
import de.kleinabersmart.tinyamp.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.tinyamp.core.data.spoken.AuthorImages
import de.kleinabersmart.tinyamp.core.data.spoken.ResumeRule
import de.kleinabersmart.tinyamp.core.data.spoken.SeekSteps
import de.kleinabersmart.tinyamp.core.data.spoken.SpokenRefresher
import de.kleinabersmart.tinyamp.core.data.spoken.SpokenSettings
import de.kleinabersmart.tinyamp.core.data.sync.LibrarySettings
import de.kleinabersmart.tinyamp.core.data.sync.LibrarySyncer
import de.kleinabersmart.tinyamp.core.data.sync.SyncFailure
import de.kleinabersmart.tinyamp.core.data.sync.SyncSettings
import de.kleinabersmart.tinyamp.core.data.di.ApplicationScope
import de.kleinabersmart.tinyamp.core.download.DownloadRepository
import de.kleinabersmart.tinyamp.core.download.DownloadSettings
import de.kleinabersmart.tinyamp.core.model.PodcastFeed
import de.kleinabersmart.tinyamp.core.model.ReplayGainMode
import de.kleinabersmart.tinyamp.core.model.ServerInfo
import de.kleinabersmart.tinyamp.core.model.SpokenMode
import de.kleinabersmart.tinyamp.core.playback.ReplayGainSettings
import de.kleinabersmart.tinyamp.ui.orOnError
import de.kleinabersmart.tinyamp.ui.theme.ThemeMode
import de.kleinabersmart.tinyamp.ui.theme.ThemeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ServerItem(
    val server: ServerInfo,
    /** Grund des letzten fehlgeschlagenen Abgleichs. */
    val error: SyncFailure?,
    /** Das gespeicherte Passwort ist nicht mehr lesbar; der Nutzer muss es beim Bearbeiten neu eingeben. */
    val passwordLost: Boolean = false,
    /** Von diesem Server wird alles automatisch heruntergeladen. */
    val autoDownload: Boolean = false,
)

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val systemColors: Boolean = false,
    val alwaysShowNavigationBar: Boolean = true,
    val useLocalMedia: Boolean = true,
    val useLocalBooks: Boolean = true,
    val useLocalPodcasts: Boolean = true,
    val servers: List<ServerItem> = emptyList(),
    /** Bis die Quellen geantwortet haben; so blitzt „Keine Server“ beim Öffnen nicht auf. */
    val isLoading: Boolean = true,
    val isFailed: Boolean = false,
    val audiobookServerCount: Int = 0,
    val podcastFeedCount: Int = 0,
    /** Archivierte Abos, nach Titel; unter Einstellungen → Podcasts reaktivierbar. */
    val archivedPodcasts: List<PodcastFeed> = emptyList(),
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
    val bookSeek: SeekSteps = SeekSteps.DEFAULT,
    val podcastSeek: SeekSteps = SeekSteps.DEFAULT,
    val bookMobileData: Boolean = false,
    /** Verzeichnisse, die dieser Build kennt, und die eingeschalteten (Konzept 12.10). */
    val podcastDirectories: List<PodcastDirectory> = emptyList(),
    val enabledPodcastDirectories: Set<PodcastDirectory> = emptySet(),
    val appleCountry: String = "",
    /** RSS-Bridge für Sendungen aus ARD Sounds im OPML-Export; leer = sie fehlen dort. */
    val rssBridge: String = "",
    /** Eingeschaltete Bereiche (Allgemein → Bereiche); die Unterseiten der anderen entfallen. */
    val areas: List<SpokenMode> = SpokenMode.entries,
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
    data class DatabaseExported(val result: DatabaseExportResult) : TransferMessage
    data object DatabaseExportFailed : TransferMessage
    data object StatsReset : TransferMessage
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
    private val opmlImporter: OpmlImporter,
    private val directorySearch: PodcastDirectorySearch,
    private val databaseImporter: DatabaseImporter,
    private val databaseExport: DatabaseExport,
    private val statsRepository: StatsRepository,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
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
                error = failures[it.sourceId],
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
            isLoading = false,
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
    }.combine(combine(themeSettings.alwaysShowNavigationBar, themeSettings.systemColors, ::Pair)) { state, (always, systemColors) ->
        state.copy(alwaysShowNavigationBar = always, systemColors = systemColors)
    }.combine(combine(spokenSettings.useLocalBooks, spokenSettings.useLocalPodcasts, ::Pair)) { state, (books, podcasts) ->
        state.copy(useLocalBooks = books, useLocalPodcasts = podcasts)
    }.combine(audiobookServers.servers) { state, list ->
        state.copy(audiobookServerCount = list.size)
    }.combine(combine(podcastRepository.feeds, podcastRepository.archivedFeeds, ::Pair)) { state, (feeds, archived) ->
        state.copy(podcastFeedCount = feeds.size, archivedPodcasts = archived)
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
    }.combine(combine(spokenSettings.bookSeek, spokenSettings.podcastSeek, ::Pair)) { state, (book, podcast) ->
        state.copy(bookSeek = book, podcastSeek = podcast)
    }.combine(spokenSettings.bookMobileData) { state, mobile ->
        state.copy(bookMobileData = mobile)
    }.combine(spokenSettings.areas) { state, areas ->
        state.copy(areas = areas)
    }.combine(combine(podcastSettings.directories, podcastSettings.appleCountry, ::Pair)) { state, (directories, country) ->
        state.copy(podcastDirectories = directorySearch.available, enabledPodcastDirectories = directories, appleCountry = country)
    }.combine(podcastSettings.rssBridge) { state, bridge ->
        state.copy(rssBridge = bridge)
    }
        .orOnError(SettingsUiState(isLoading = false, isFailed = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Holt ein archiviertes Abo zurück; im App-Scope, damit der Abgleich auch nach Verlassen der Seite fertig wird. */
    fun reactivatePodcast(feedId: String) {
        applicationScope.launch { podcastRepository.reactivate(feedId) }
    }

    /** Beendet ein archiviertes Abo endgültig (Folgen und Fortschritt weg; Downloads gibt es seit dem Archivieren keine). */
    fun deleteArchivedPodcast(feedId: String) {
        applicationScope.launch { podcastRepository.unsubscribe(feedId) }
    }

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

    fun setBookSeek(steps: SeekSteps) {
        viewModelScope.launch { spokenSettings.setBookSeek(steps) }
    }

    fun setPodcastSeek(steps: SeekSteps) {
        viewModelScope.launch { spokenSettings.setPodcastSeek(steps) }
    }

    /** Wartende Buch-Downloads bekommen die neue Netzbedingung sofort (BookDownloads). */
    fun setBookMobileData(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setBookMobileData(enabled) }
    }

    fun setRestartChapter(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setRestartChapter(enabled) }
    }

    fun setPodcastMobileData(enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setUseMobileData(enabled) }
    }

    fun setPodcastDirectory(directory: PodcastDirectory, enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setDirectory(directory, enabled) }
    }

    fun setAppleCountry(country: String) {
        viewModelScope.launch { podcastSettings.setAppleCountry(country) }
    }

    fun setRssBridge(url: String) {
        viewModelScope.launch { podcastSettings.setRssBridge(url) }
    }

    /** Die Planung des periodischen Auftrags folgt der Einstellung ([de.kleinabersmart.tinyamp.core.data.sync.LibraryRefresher]). */
    fun setSyncIntervalDays(days: Int) {
        viewModelScope.launch { librarySettings.setSyncIntervalDays(days) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeSettings.setMode(mode) }
    }

    fun setSystemColors(enabled: Boolean) {
        viewModelScope.launch { themeSettings.setSystemColors(enabled) }
    }

    fun setAlwaysShowNavigationBar(enabled: Boolean) {
        viewModelScope.launch { themeSettings.setAlwaysShowNavigationBar(enabled) }
    }

    /** Blendet einen Bereich ein oder aus; der letzte eingeschaltete bleibt an ([SpokenSettings.setAreaEnabled]). */
    fun setAreaEnabled(area: SpokenMode, enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setAreaEnabled(area, enabled) }
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

    /**
     * Liest die Abos aus [uri] und übernimmt sie ([replace]: alle anderen Abos entfallen); danach werden die Feeds abgeglichen.
     * Im- und Exporte laufen im App-Scope: Verlässt der Nutzer die Seite, bräche er sonst mitten im Löschen bzw. Schreiben ab.
     */
    fun importOpml(uri: Uri, replace: Boolean) {
        viewModelScope.launch {
            val result = applicationScope.async { opmlImporter.import(uri, replace) }.await()
            _transferMessages.send(if (result != null) TransferMessage.Imported(result) else TransferMessage.ImportFailed)
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
            try {
                _transferMessages.send(TransferMessage.DatabaseImportStarted)
                // Die Datei gehört dem Auftrag im App-Scope; er räumt sie selbst weg, auch wenn die Seite vorher geht.
                val imported = applicationScope.async {
                    val file = File(context.cacheDir, "import.db")
                    try {
                        val backup = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                                ?: throw IOException("Datei nicht lesbar")
                            DatabaseReader.read(file)
                        }
                        databaseImporter.import(backup)
                    } finally {
                        file.delete()
                    }
                }.await()
                _transferMessages.send(TransferMessage.DatabaseImported(imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Backup-Import fehlgeschlagen", e)
                _transferMessages.send(TransferMessage.DatabaseImportFailed)
            } finally {
                backupRunning = false
            }
        }
    }

    /** Löscht die von der App gezählte Hörzeit der Bücher; die Zahlen des Servers bleiben. */
    fun resetBookStats() {
        viewModelScope.launch {
            statsRepository.resetBookStats()
            _transferMessages.send(TransferMessage.StatsReset)
        }
    }

    /** Löscht die gesamte Podcast-Statistik (gezählte und importierte Hörzeit). */
    fun resetPodcastStats() {
        viewModelScope.launch {
            statsRepository.resetPodcastStats()
            _transferMessages.send(TransferMessage.StatsReset)
        }
    }

    /** Schreibt Abos, Hörfortschritt und gehörte Zeit als Datenbank-Datei nach [uri]. */
    fun exportDatabase(uri: Uri) {
        viewModelScope.launch {
            val result = databaseExport.export(uri)
            _transferMessages.send(if (result != null) TransferMessage.DatabaseExported(result) else TransferMessage.DatabaseExportFailed)
        }
    }

    /** Schreibt die Abos als OPML nach [uri]. */
    fun exportOpml(uri: Uri, title: String) {
        viewModelScope.launch {
            val message = try {
                val created = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
                val text = podcastRepository.exportOpml(title, created, podcastSettings.rssBridge.first().ifBlank { null })
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

    /** Gleicht die eingeschalteten Bereiche ab. */
    fun refreshAll() {
        val areas = state.value.areas
        if (SpokenMode.MUSIC in areas) refreshMusic()
        if (SpokenMode.BOOKS in areas) refreshAudiobooks()
        if (SpokenMode.PODCASTS in areas) refreshPodcasts()
    }

    fun setDownloadWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            downloadSettings.setWifiOnly(wifiOnly)
            downloadRepository.rescheduleAfterSettingsChange()
        }
    }

    /** Die Downloads selbst stößt [de.kleinabersmart.tinyamp.core.download.AutoDownloads] an. */
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
