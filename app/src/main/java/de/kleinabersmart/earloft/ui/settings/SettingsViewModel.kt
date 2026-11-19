package de.kleinabersmart.earloft.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.DatabaseReader
import de.kleinabersmart.earloft.core.data.podcast.DatabaseExportResult
import de.kleinabersmart.earloft.core.data.podcast.DatabaseImporter
import de.kleinabersmart.earloft.core.data.podcast.DatabaseImportResult
import de.kleinabersmart.earloft.core.data.podcast.OpmlImportResult
import de.kleinabersmart.earloft.core.data.podcast.PodcastDirectorySearch
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.earloft.ui.spoken.OpmlImporter
import de.kleinabersmart.earloft.core.data.repository.ServerRepository
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.sync.LibraryRefresher
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.ArtworkSettings
import de.kleinabersmart.earloft.core.data.repository.LyricsRepository
import de.kleinabersmart.earloft.core.data.repository.LyricsSettings
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.AuthorImages
import de.kleinabersmart.earloft.core.data.spoken.ResumeRule
import de.kleinabersmart.earloft.core.data.spoken.BookButtons
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.spoken.LocalBooks
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.data.sync.SyncFailure
import de.kleinabersmart.earloft.core.data.sync.SyncSettings
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.download.DownloadSettings
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.MobileStreamQuality
import de.kleinabersmart.earloft.core.model.ReplayGainMode
import de.kleinabersmart.earloft.core.model.ServerInfo
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.EndlessMixSettings
import de.kleinabersmart.earloft.core.playback.ReplayGainSettings
import de.kleinabersmart.earloft.ui.orOnError
import de.kleinabersmart.earloft.ui.theme.ThemeMode
import de.kleinabersmart.earloft.ui.theme.ThemeSettings
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
    /** Reason for the last failed sync. */
    val error: SyncFailure?,
    /** The stored password is no longer readable; the user has to re-enter it when editing. */
    val passwordLost: Boolean = false,
    /** Everything from this server is downloaded automatically. */
    val autoDownload: Boolean = false,
)

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val systemColors: Boolean = false,
    val playerCoverColors: Boolean = true,
    val alwaysShowNavigationBar: Boolean = true,
    val useLocalMedia: Boolean = false,
    val useLocalBooks: Boolean = false,
    /** Audiobooks and radio plays from ARD Sounds (`books_improvements.md` M9). */
    val ardSoundsBooks: Boolean = false,
    /** The "Audiobooks" folder has been granted (covers and texts of the book folders, `books_improvements.md` M7). */
    val localBooksFolderShared: Boolean = false,
    val useLocalPodcasts: Boolean = false,
    val servers: List<ServerItem> = emptyList(),
    /** Until the sources have answered; that way "No servers" doesn't flash up on opening. */
    val isLoading: Boolean = true,
    val isFailed: Boolean = false,
    val audiobookServerCount: Int = 0,
    val podcastFeedCount: Int = 0,
    /** Archived subscriptions, by title; can be reactivated under Settings → Podcasts. */
    val archivedPodcasts: List<PodcastFeed> = emptyList(),
    /** Subscriptions whose sync has been failing for several attempts (concept 12.10). */
    val problemPodcasts: List<PodcastFeed> = emptyList(),
    val syncIntervalDays: Int = LibrarySettings.DEFAULT_SYNC_DAYS,
    val mobileStreamQuality: MobileStreamQuality = MobileStreamQuality.ORIGINAL,
    val isSyncing: Boolean = false,
    val downloadWifiOnly: Boolean = true,
    val autoDownloadFavorites: Boolean = false,
    val autoDownloadFavoriteArtists: Boolean = false,
    val autoDownloadNewestAlbums: Int = 0,
    val autoDownloadMostPlayedAlbums: Int = 0,
    val scrobble: Boolean = true,
    val endlessMix: Boolean = false,
    val replayGainMode: ReplayGainMode = ReplayGainMode.AUTO,
    val preAmpDb: Float = 0f,
    val artistImages: Boolean = false,
    val albumCovers: Boolean = false,
    val authorImages: Boolean = false,
    val onlineLyrics: Boolean = false,
    val podcastRefreshHours: Int = PodcastSettings.DEFAULT_REFRESH_HOURS,
    val podcastMobileData: Boolean = false,
    val podcastDeleteHeardDays: Int = PodcastSettings.DEFAULT_DELETE_HEARD_DAYS,
    val podcastAutoDownloadCount: Int = PodcastSettings.DEFAULT_AUTO_DOWNLOAD_COUNT,
    val podcastAutoDownloadNew: Boolean = false,
    val podcastAutoPlayNext: Boolean = true,
    val podcastSwipeActions: Boolean = true,
    val bookResume: ResumeRule = ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S),
    val podcastResume: ResumeRule = ResumeRule(ResumeRule.DEFAULT_AFTER_S, ResumeRule.DEFAULT_REWIND_S),
    val restartChapter: Boolean = false,
    /** Buttons in the notification and Auto for books: seek or chapter. */
    val bookButtons: BookButtons = BookButtons.SEEK,
    /** Shaking extends the sleep timer (books and podcasts, one shared switch). */
    val shakeToExtend: Boolean = true,
    val bookSeek: SeekSteps = SeekSteps.DEFAULT,
    val podcastSeek: SeekSteps = SeekSteps.DEFAULT,
    /** Defaults for speed, trim silence and volume boost (concept 12.4). */
    val bookEffects: SpokenEffects = SpokenEffects.DEFAULT,
    val podcastEffects: SpokenEffects = SpokenEffects.DEFAULT,
    val bookMobileData: Boolean = false,
    /** Book auto-download and cleanup (concept 12.8). */
    val bookAutoStarted: Boolean = false,
    val bookAutoNextInSeries: Boolean = false,
    val bookLoadExpiring: Boolean = true,
    val bookDeleteFinishedDays: Int = PodcastSettings.DELETE_HEARD_NEVER,
    /** Ask before streaming books and episodes over mobile data. */
    val askBeforeMobileStreaming: Boolean = false,
    /** Directories this build knows, and the enabled ones (concept 12.10). */
    val podcastDirectories: List<PodcastDirectory> = emptyList(),
    val enabledPodcastDirectories: Set<PodcastDirectory> = emptySet(),
    val appleCountry: String = "",
    /** RSS bridge for ARD Sounds shows in the OPML export; empty = they are missing there. */
    val rssBridge: String = "",
    /** Enabled areas (General → Areas); the subpages of the others are omitted. */
    val areas: List<SpokenMode> = SpokenMode.entries,
)

private data class GeneralSettings(
    val themeMode: ThemeMode,
    val useLocalMedia: Boolean,
    val artistImages: Boolean,
    val albumCovers: Boolean,
)

/** Outcome of an OPML import or export. */
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
    private val endlessMixSettings: EndlessMixSettings,
    private val themeSettings: ThemeSettings,
    private val librarySettings: LibrarySettings,
    private val spokenSettings: SpokenSettings,
    private val podcastSettings: PodcastSettings,
    private val artworkSettings: ArtworkSettings,
    private val artworkRepository: ArtworkRepository,
    private val lyricsSettings: LyricsSettings,
    private val lyricsRepository: LyricsRepository,
    private val authorImages: AuthorImages,
    private val registry: DataSourceRegistry,
    private val spokenRefresher: SpokenRefresher,
    private val localBooks: LocalBooks,
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
        // Otherwise the servers are only loaded on first access; without this the hint about lost passwords would be missing.
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
    }.combine(combine(artworkSettings.authorImages, lyricsSettings.onlineLyrics, ::Pair)) { state, (authors, lyrics) ->
        state.copy(authorImages = authors, onlineLyrics = lyrics)
    }.combine(combine(themeSettings.alwaysShowNavigationBar, themeSettings.systemColors, ::Pair)) { state, (always, systemColors) ->
        state.copy(alwaysShowNavigationBar = always, systemColors = systemColors)
    }.combine(themeSettings.playerCoverColors) { state, player ->
        state.copy(playerCoverColors = player)
    }.combine(combine(spokenSettings.useLocalBooks, spokenSettings.useLocalPodcasts, localBooks.folderShared, ::Triple)) { state, (books, podcasts, folder) ->
        state.copy(useLocalBooks = books, useLocalPodcasts = podcasts, localBooksFolderShared = folder)
    }.combine(combine(audiobookServers.servers, spokenSettings.ardSoundsBooks, ::Pair)) { state, (list, ard) ->
        state.copy(audiobookServerCount = list.size, ardSoundsBooks = ard)
    }.combine(combine(podcastRepository.feeds, podcastRepository.archivedFeeds, ::Pair)) { state, (feeds, archived) ->
        state.copy(podcastFeedCount = feeds.size, archivedPodcasts = archived, problemPodcasts = feeds.filter { it.hasProblem })
    }.combine(combine(librarySettings.syncIntervalDays, librarySettings.mobileStreamQuality, ::Pair)) { state, (days, quality) ->
        state.copy(syncIntervalDays = days, mobileStreamQuality = quality)
    }.combine(combine(downloadSettings.autoDownloadNewestAlbums, downloadSettings.autoDownloadMostPlayedAlbums, ::Pair)) { state, (newest, mostPlayed) ->
        state.copy(autoDownloadNewestAlbums = newest, autoDownloadMostPlayedAlbums = mostPlayed)
    }.combine(combine(downloadSettings.autoDownloadFavoriteArtists, endlessMixSettings.enabled, ::Pair)) { state, (artists, endless) ->
        state.copy(autoDownloadFavoriteArtists = artists, endlessMix = endless)
    }.combine(
        combine(podcastSettings.refreshHours, podcastSettings.useMobileData, podcastSettings.deleteHeardAfterDays, ::Triple),
    ) { state, (hours, mobile, deleteHeardDays) ->
        state.copy(podcastRefreshHours = hours, podcastMobileData = mobile, podcastDeleteHeardDays = deleteHeardDays)
    }.combine(combine(podcastSettings.autoDownloadCount, podcastSettings.autoDownloadNew, ::Pair)) { state, (count, autoNew) ->
        state.copy(podcastAutoDownloadCount = count, podcastAutoDownloadNew = autoNew)
    }.combine(combine(podcastSettings.autoPlayNext, podcastSettings.swipeActions, ::Pair)) { state, (autoPlay, swipe) ->
        state.copy(podcastAutoPlayNext = autoPlay, podcastSwipeActions = swipe)
    }.combine(combine(spokenSettings.bookResume, spokenSettings.podcastResume, spokenSettings.restartChapter, ::Triple)) { state, (book, podcast, chapter) ->
        state.copy(bookResume = book, podcastResume = podcast, restartChapter = chapter)
    }.combine(combine(spokenSettings.bookSeek, spokenSettings.podcastSeek, spokenSettings.bookButtons, ::Triple)) { state, (book, podcast, buttons) ->
        state.copy(bookSeek = book, podcastSeek = podcast, bookButtons = buttons)
    }.combine(combine(spokenSettings.bookMobileData, spokenSettings.shakeToExtend, ::Pair)) { state, (mobile, shake) ->
        state.copy(bookMobileData = mobile, shakeToExtend = shake)
    }.combine(
        combine(spokenSettings.bookAutoStarted, spokenSettings.bookAutoNextInSeries, spokenSettings.bookDeleteFinishedDays, ::Triple),
    ) { state, (autoStarted, autoNext, deleteDays) ->
        state.copy(bookAutoStarted = autoStarted, bookAutoNextInSeries = autoNext, bookDeleteFinishedDays = deleteDays)
    }.combine(spokenSettings.bookLoadExpiring) { state, expiring ->
        state.copy(bookLoadExpiring = expiring)
    }.combine(spokenSettings.askBeforeMobileStreaming) { state, ask ->
        state.copy(askBeforeMobileStreaming = ask)
    }.combine(combine(spokenSettings.bookEffects, spokenSettings.podcastEffects, ::Pair)) { state, (book, podcast) ->
        state.copy(bookEffects = book, podcastEffects = podcast)
    }.combine(spokenSettings.areas) { state, areas ->
        state.copy(areas = areas)
    }.combine(combine(podcastSettings.directories, podcastSettings.appleCountry, ::Pair)) { state, (directories, country) ->
        state.copy(podcastDirectories = directorySearch.available, enabledPodcastDirectories = directories, appleCountry = country)
    }.combine(podcastSettings.rssBridge) { state, bridge ->
        state.copy(rssBridge = bridge)
    }
        .orOnError(SettingsUiState(isLoading = false, isFailed = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Brings back an archived subscription; in the app scope so the sync also finishes after leaving the page. */
    fun reactivatePodcast(feedId: String) {
        applicationScope.launch { podcastRepository.reactivate(feedId) }
    }

    /** Ends an archived subscription for good (episodes and progress gone; there have been no downloads since archiving). */
    fun deleteArchivedPodcast(feedId: String) {
        applicationScope.launch { podcastRepository.unsubscribe(feedId) }
    }

    /** Scheduling of the periodic job follows the setting (PodcastRefresher). */
    fun setPodcastRefreshHours(hours: Int) {
        viewModelScope.launch { podcastSettings.setRefreshHours(hours) }
    }

    /** The auto-cleanup (EpisodeDownloads) follows the setting. */
    fun setPodcastDeleteHeardDays(days: Int) {
        viewModelScope.launch { podcastSettings.setDeleteHeardAfterDays(days) }
    }

    /** With a larger number, the auto-download (EpisodeDownloads) fetches episodes right away. */
    fun setPodcastAutoDownloadCount(count: Int) {
        viewModelScope.launch { podcastSettings.setAutoDownloadCount(count) }
    }

    /** Default only for future subscriptions; existing ones keep their setting. */
    fun setPodcastAutoDownloadNew(enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setAutoDownloadNew(enabled) }
    }

    fun setPodcastAutoPlayNext(enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setAutoPlayNext(enabled) }
    }

    fun setPodcastSwipeActions(enabled: Boolean) {
        viewModelScope.launch { podcastSettings.setSwipeActions(enabled) }
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

    /** Waiting book downloads get the new network constraint immediately (BookDownloads). */
    fun setBookMobileData(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setBookMobileData(enabled) }
    }

    fun setAskBeforeMobileStreaming(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setAskBeforeMobileStreaming(enabled) }
    }

    fun setBookAutoStarted(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setBookAutoStarted(enabled) }
    }

    fun setBookLoadExpiring(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setBookLoadExpiring(enabled) }
    }

    fun setBookAutoNextInSeries(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setBookAutoNextInSeries(enabled) }
    }

    fun setBookDeleteFinishedDays(days: Int) {
        viewModelScope.launch { spokenSettings.setBookDeleteFinishedDays(days) }
    }

    fun setBookButtons(buttons: BookButtons) {
        viewModelScope.launch { spokenSettings.setBookButtons(buttons) }
    }

    fun setRestartChapter(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setRestartChapter(enabled) }
    }

    fun setShakeToExtend(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setShakeToExtend(enabled) }
    }

    /** Default for the books' speed and effects; books with their own values keep them. */
    fun setBookEffects(effects: SpokenEffects) {
        viewModelScope.launch { spokenSettings.setBookEffects(effects) }
    }

    /** Default for the podcasts' speed and effects; subscriptions and episodes with their own values keep them. */
    fun setPodcastEffects(effects: SpokenEffects) {
        viewModelScope.launch { spokenSettings.setPodcastEffects(effects) }
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

    /** Scheduling of the periodic job follows the setting ([de.kleinabersmart.earloft.core.data.sync.LibraryRefresher]). */
    fun setSyncIntervalDays(days: Int) {
        viewModelScope.launch { librarySettings.setSyncIntervalDays(days) }
    }

    fun setMobileStreamQuality(quality: MobileStreamQuality) {
        viewModelScope.launch { librarySettings.setMobileStreamQuality(quality) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themeSettings.setMode(mode) }
    }

    fun setSystemColors(enabled: Boolean) {
        viewModelScope.launch { themeSettings.setSystemColors(enabled) }
    }

    fun setPlayerCoverColors(enabled: Boolean) {
        viewModelScope.launch { themeSettings.setPlayerCoverColors(enabled) }
    }

    fun setAlwaysShowNavigationBar(enabled: Boolean) {
        viewModelScope.launch { themeSettings.setAlwaysShowNavigationBar(enabled) }
    }

    /** Shows or hides an area; the last enabled one stays on ([SpokenSettings.setAreaEnabled]). */
    fun setAreaEnabled(area: SpokenMode, enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setAreaEnabled(area, enabled) }
    }

    /** Scanning or removing the local songs is handled by the [LibraryRefresher]. */
    fun setUseLocalMedia(enabled: Boolean) {
        viewModelScope.launch { librarySettings.setUseLocalMedia(enabled) }
    }

    /** Scanning or removing the books from the device is handled by the [SpokenRefresher]. */
    fun setUseLocalBooks(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setUseLocalBooks(enabled) }
    }

    fun setArdSoundsBooks(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setArdSoundsBooks(enabled) }
    }

    /** This is where selecting the "Audiobooks" folder starts. */
    val localBooksFolderStart: Uri get() = localBooks.folderPickerStart

    private val _folderRejected = Channel<Unit>(Channel.CONFLATED)

    /** The chosen grant wasn't "Audiobooks" (to be shown once). */
    val folderRejected: Flow<Unit> = _folderRejected.receiveAsFlow()

    /** Folder chosen in the picker; the books are then re-read in the background. */
    fun shareLocalBooksFolder(tree: Uri) {
        applicationScope.launch { if (!localBooks.shareFolder(tree)) _folderRejected.trySend(Unit) }
    }

    fun unshareLocalBooksFolder() {
        applicationScope.launch { localBooks.unshareFolder() }
    }

    /** Scanning or removing the podcast files from the device is handled by the [SpokenRefresher]. */
    fun setUseLocalPodcasts(enabled: Boolean) {
        viewModelScope.launch { spokenSettings.setUseLocalPodcasts(enabled) }
    }

    /** When turned off, the images already downloaded are deleted. */
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

    /** The repository forgets the texts that may change as a result. */
    fun setOnlineLyrics(enabled: Boolean) {
        viewModelScope.launch { lyricsRepository.setOnlineLyrics(enabled) }
    }

    /** When turned off, the author images already downloaded from the internet are deleted. */
    fun setAuthorImages(enabled: Boolean) {
        viewModelScope.launch {
            artworkSettings.setAuthorImages(enabled)
            if (!enabled) authorImages.clearOnlineImages()
        }
    }

    fun syncNow(sourceId: String) = serverRepository.syncNow(sourceId)

    /** Local music and all Navidrome servers. */
    fun refreshMusic() = refresher.refreshAll()

    fun refreshAudiobooks() = spokenRefresher.refreshAll()

    fun refreshPodcasts() = podcastRefresher.refreshAll()

    private val _transferMessages = Channel<TransferMessage>(Channel.BUFFERED)

    /** Result of the last OPML import or export, to be shown once. */
    val transferMessages: Flow<TransferMessage> = _transferMessages.receiveAsFlow()

    /**
     * Reads the subscriptions from [uri] and adopts them ([replace]: all other subscriptions are dropped); then the feeds are synced.
     * Imports and exports run in the app scope: otherwise, if the user left the page, it would abort mid-delete or mid-write.
     */
    fun importOpml(uri: Uri, replace: Boolean) {
        viewModelScope.launch {
            val result = applicationScope.async { opmlImporter.import(uri, replace) }.await()
            _transferMessages.send(if (result != null) TransferMessage.Imported(result) else TransferMessage.ImportFailed)
        }
    }

    private var backupRunning = false

    /**
     * Reads the database file from [uri]: missing active subscriptions, listening progress and listening time (statistics).
     * Takes a while because new feeds are synced; a second call in the meantime is ignored.
     */
    fun importImportData(uri: Uri) {
        if (backupRunning) return
        backupRunning = true
        viewModelScope.launch {
            try {
                _transferMessages.send(TransferMessage.DatabaseImportStarted)
                // The file belongs to the job in the app scope; it cleans it up itself, even if the page goes away first.
                val imported = applicationScope.async {
                    val file = File(context.cacheDir, "import.db")
                    try {
                        val backup = withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                                ?: throw IOException("File not readable")
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
                Log.w(TAG, "Backup import failed", e)
                _transferMessages.send(TransferMessage.DatabaseImportFailed)
            } finally {
                backupRunning = false
            }
        }
    }

    /** Deletes the books' listening time counted by the app; the server's numbers remain. */
    fun resetBookStats() {
        viewModelScope.launch {
            statsRepository.resetBookStats()
            _transferMessages.send(TransferMessage.StatsReset)
        }
    }

    /** Deletes the entire podcast statistics (counted and imported listening time). */
    fun resetPodcastStats() {
        viewModelScope.launch {
            statsRepository.resetPodcastStats()
            _transferMessages.send(TransferMessage.StatsReset)
        }
    }

    /** Writes subscriptions, listening progress and listening time as a database file to [uri]. */
    fun exportDatabase(uri: Uri) {
        viewModelScope.launch {
            val result = databaseExport.export(uri)
            _transferMessages.send(if (result != null) TransferMessage.DatabaseExported(result) else TransferMessage.DatabaseExportFailed)
        }
    }

    /** Writes the subscriptions as OPML to [uri]. */
    fun exportOpml(uri: Uri, title: String) {
        viewModelScope.launch {
            val message = try {
                val created = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
                val text = podcastRepository.exportOpml(title, created, podcastSettings.rssBridge.first().ifBlank { null })
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("File not writable")
                }
                TransferMessage.Exported
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "OPML export failed", e)
                TransferMessage.ExportFailed
            }
            _transferMessages.send(message)
        }
    }

    /** Syncs the enabled areas. */
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

    /** The downloads themselves are triggered by [de.kleinabersmart.earloft.core.download.AutoDownloads]. */
    fun setAutoDownloadFavorites(enabled: Boolean) {
        viewModelScope.launch { downloadSettings.setAutoDownloadFavorites(enabled) }
    }

    fun setAutoDownloadFavoriteArtists(enabled: Boolean) {
        viewModelScope.launch { downloadSettings.setAutoDownloadFavoriteArtists(enabled) }
    }

    fun setAutoDownloadNewestAlbums(count: Int) {
        viewModelScope.launch { downloadSettings.setAutoDownloadNewestAlbums(count) }
    }

    fun setAutoDownloadMostPlayedAlbums(count: Int) {
        viewModelScope.launch { downloadSettings.setAutoDownloadMostPlayedAlbums(count) }
    }

    fun setScrobble(enabled: Boolean) {
        viewModelScope.launch { syncSettings.setScrobble(enabled) }
    }

    fun setEndlessMix(enabled: Boolean) {
        viewModelScope.launch { endlessMixSettings.setEnabled(enabled) }
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
