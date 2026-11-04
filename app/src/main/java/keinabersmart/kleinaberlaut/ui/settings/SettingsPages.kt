package keinabersmart.kleinaberlaut.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastSettings
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySettings
import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.LoadFailedHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.permission.rememberAudioPermissionRequester
import keinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import keinabersmart.kleinaberlaut.ui.spoken.AudiobookshelfSection
import keinabersmart.kleinaberlaut.ui.theme.ThemeMode
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

// Musik, Podcasts und Bücher gliedern sich gleich: Quellen · Aktualisierung · Bilder · Wiedergabe · Downloads.
// Abschnitte ohne Inhalt entfallen (Konzept 6).

/** „Jetzt abgleichen“ o. Ä. als Zeile des Abschnitts „Aktualisierung“. */
@Composable
private fun RefreshRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Was für alle Bereiche gilt: Darstellung, Suche, Downloads, Abgleich. */
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingTheme by rememberSaveable { mutableStateOf(false) }

    SettingsPage(stringResource(R.string.settings_general), onBack, modifier) {
        item(key = "appearance") { SectionHeader(stringResource(R.string.settings_section_appearance)) }
        item(key = "theme") {
            ClickRow(
                title = stringResource(R.string.settings_theme),
                value = stringResource(state.themeMode.label),
                icon = DesignR.drawable.ic_contrast,
                onClick = { choosingTheme = true },
            )
        }

        item(key = "always-nav") {
            SwitchRow(
                title = stringResource(R.string.settings_always_navigation_bar),
                summary = stringResource(R.string.settings_always_navigation_bar_summary),
                icon = R.drawable.ic_nav_home,
                checked = state.alwaysShowNavigationBar,
                onCheckedChange = viewModel::setAlwaysShowNavigationBar,
            )
        }

        item(key = "downloads") { SectionHeader(stringResource(R.string.nav_downloads)) }
        item(key = "wifi-only") {
            SwitchRow(
                title = stringResource(R.string.settings_wifi_only),
                summary = stringResource(R.string.settings_wifi_only_summary),
                icon = DesignR.drawable.ic_download,
                checked = state.downloadWifiOnly,
                onCheckedChange = viewModel::setDownloadWifiOnly,
            )
        }
        item(key = "downloads-open") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.nav_downloads)) },
                supportingContent = { Text(stringResource(R.string.settings_downloads_summary)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenDownloads),
            )
        }

        item(key = "refresh") { SectionHeader(stringResource(R.string.settings_section_refresh)) }
        item(key = "refresh-all") { RefreshRow(stringResource(R.string.settings_refresh_all), viewModel::refreshAll) }
    }

    if (choosingTheme) {
        ChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries,
            selected = state.themeMode,
            label = { stringResource(it.label) },
            onSelect = {
                viewModel.setThemeMode(it)
                choosingTheme = false
            },
            onDismiss = { choosingTheme = false },
        )
    }
}

@Composable
fun MusicSettingsScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (sourceId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var serverToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingReplayGain by rememberSaveable { mutableStateOf(false) }
    var choosingSyncInterval by rememberSaveable { mutableStateOf(false) }
    // Einschalten erst mit Berechtigung; sonst stünde gleich wieder die Berechtigungsseite davor.
    val requestAudioPermission = rememberAudioPermissionRequester { granted ->
        if (granted) viewModel.setUseLocalMedia(true)
    }

    SettingsPage(stringResource(R.string.settings_music), onBack, modifier) {
        item(key = "sources") { SectionHeader(stringResource(R.string.settings_section_sources)) }
        item(key = "local-media") {
            SwitchRow(
                title = stringResource(R.string.settings_local_media),
                summary = stringResource(R.string.settings_local_media_summary),
                icon = DesignR.drawable.ic_music_note,
                checked = state.useLocalMedia,
                onCheckedChange = { enabled ->
                    if (enabled) requestAudioPermission() else viewModel.setUseLocalMedia(false)
                },
            )
        }
        if (state.isFailed) {
            item(key = "servers-failed") { LoadFailedHint() }
        } else if (!state.isLoading && state.servers.isEmpty()) {
            item(key = "no-servers") { EmptyHint(stringResource(R.string.settings_no_servers)) }
        }
        items(state.servers, key = { it.server.sourceId }) { item ->
            ServerRow(
                item = item,
                onEdit = { onEditServer(item.server.sourceId) },
                onSyncNow = { viewModel.syncNow(item.server.sourceId) },
                onEnabledChange = { viewModel.setServerEnabled(item.server.sourceId, it) },
                onRemove = { serverToRemove = item.server.sourceId },
            )
        }
        item(key = "add") {
            FilledTonalButton(onClick = onAddServer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.settings_add_server), modifier = Modifier.padding(start = 8.dp))
            }
        }

        item(key = "refresh") { SectionHeader(stringResource(R.string.settings_section_refresh)) }
        item(key = "sync-interval") {
            ClickRow(
                title = stringResource(R.string.settings_music_sync_interval),
                value = syncIntervalLabel(state.syncIntervalDays),
                icon = DesignR.drawable.ic_sync,
                onClick = { choosingSyncInterval = true },
            )
        }
        item(key = "refresh-music") { RefreshRow(stringResource(R.string.settings_refresh_music), viewModel::refreshMusic) }

        item(key = "images") { SectionHeader(stringResource(R.string.settings_section_images)) }
        item(key = "artist-images") {
            SwitchRow(
                title = stringResource(R.string.settings_artist_images),
                summary = stringResource(R.string.settings_artist_images_summary),
                icon = DesignR.drawable.ic_person,
                checked = state.artistImages,
                onCheckedChange = viewModel::setArtistImages,
            )
        }
        item(key = "album-covers") {
            SwitchRow(
                title = stringResource(R.string.settings_album_covers),
                summary = stringResource(R.string.settings_album_covers_summary),
                icon = DesignR.drawable.ic_music_note,
                checked = state.albumCovers,
                onCheckedChange = viewModel::setAlbumCovers,
            )
        }

        item(key = "playback") { SectionHeader(stringResource(R.string.settings_playback)) }
        item(key = "replay-gain") {
            ClickRow(
                title = stringResource(R.string.settings_replay_gain),
                value = stringResource(state.replayGainMode.label),
                icon = DesignR.drawable.ic_volume_up,
                onClick = { choosingReplayGain = true },
            )
        }
        item(key = "pre-amp") {
            PreAmpRow(
                preAmpDb = state.preAmpDb,
                enabled = state.replayGainMode != ReplayGainMode.OFF,
                onPreAmpChange = viewModel::setPreAmpDb,
            )
        }
        item(key = "scrobble") {
            SwitchRow(
                title = stringResource(R.string.settings_scrobble),
                summary = stringResource(R.string.settings_scrobble_summary),
                icon = DesignR.drawable.ic_cloud,
                checked = state.scrobble,
                onCheckedChange = viewModel::setScrobble,
            )
        }

        item(key = "downloads") { SectionHeader(stringResource(R.string.nav_downloads)) }
        item(key = "auto-favorites") {
            SwitchRow(
                title = stringResource(R.string.settings_auto_download_favorites),
                summary = stringResource(R.string.settings_auto_download_favorites_summary),
                icon = DesignR.drawable.ic_favorite,
                checked = state.autoDownloadFavorites,
                onCheckedChange = viewModel::setAutoDownloadFavorites,
            )
        }
    }

    if (choosingSyncInterval) {
        ChoiceDialog(
            title = stringResource(R.string.settings_music_sync_interval),
            options = LibrarySettings.SYNC_DAY_CHOICES,
            selected = state.syncIntervalDays,
            label = { syncIntervalLabel(it) },
            onSelect = {
                viewModel.setSyncIntervalDays(it)
                choosingSyncInterval = false
            },
            onDismiss = { choosingSyncInterval = false },
        )
    }

    if (choosingReplayGain) {
        ChoiceDialog(
            title = stringResource(R.string.settings_replay_gain),
            options = ReplayGainMode.entries,
            selected = state.replayGainMode,
            label = { stringResource(it.label) },
            summary = { if (it == ReplayGainMode.AUTO) stringResource(R.string.replay_gain_auto_summary) else null },
            onSelect = {
                viewModel.setReplayGainMode(it)
                choosingReplayGain = false
            },
            onDismiss = { choosingReplayGain = false },
        )
    }

    val removing = state.servers.firstOrNull { it.server.sourceId == serverToRemove }?.server
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { serverToRemove = null },
            title = { Text(stringResource(R.string.server_remove_title, removing.name)) },
            text = { Text(stringResource(R.string.server_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(removing.sourceId)
                    serverToRemove = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { serverToRemove = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Podcasts haben keine Server (die Feeds liegen in der Bibliothek); Quelle ist nur der Ordner auf dem Gerät. */
@Composable
fun PodcastsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingRefresh by rememberSaveable { mutableStateOf(false) }
    var choosingDeleteHeard by rememberSaveable { mutableStateOf(false) }
    var choosingAutoCount by rememberSaveable { mutableStateOf(false) }
    var confirmingStatsReset by rememberSaveable { mutableStateOf(false) }
    // Einschalten erst mit Berechtigung, sonst bliebe der Schalter an und es erschiene nichts.
    val requestAudioPermission = rememberAudioPermissionRequester { granted ->
        if (granted) viewModel.setUseLocalPodcasts(true)
    }
    // Beim Import fragt ein Dialog, ob die Abos hinzugefügt oder ersetzt werden.
    var opmlToImport by rememberSaveable { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { opmlToImport = it }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImportData(uri)
    }
    val databaseExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportDatabase(uri)
    }
    val exportName = stringResource(R.string.settings_opml_title)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) viewModel.exportOpml(uri, exportName)
    }
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.transferMessages.collect { message ->
            val text = when (message) {
                is TransferMessage.Imported -> resources.getQuantityString(
                    R.plurals.settings_opml_imported,
                    message.result.added,
                    message.result.added,
                    message.result.skipped,
                    message.result.removed,
                )
                TransferMessage.ImportFailed -> resources.getString(R.string.settings_opml_import_failed)
                TransferMessage.Exported -> resources.getString(R.string.settings_opml_exported)
                TransferMessage.ExportFailed -> resources.getString(R.string.settings_opml_export_failed)
                TransferMessage.DatabaseImportStarted -> resources.getString(R.string.settings_db_import_started)
                is TransferMessage.DatabaseImported -> resources.getString(
                    R.string.settings_db_import_imported,
                    message.result.feedsAdded,
                    message.result.progressApplied,
                    message.result.progressKept + message.result.progressMissing,
                    message.result.statsImported,
                )
                is TransferMessage.DatabaseExported -> resources.getString(
                    R.string.settings_db_export_done,
                    message.result.feeds,
                    message.result.episodes,
                )
                TransferMessage.DatabaseExportFailed -> resources.getString(R.string.settings_db_export_failed)
                TransferMessage.StatsReset -> resources.getString(R.string.settings_stats_reset_done)
                TransferMessage.DatabaseImportFailed -> resources.getString(R.string.settings_db_import_failed)
            }
            snackbars.show(text, SnackbarDuration.Long)
        }
    }

    SettingsPage(stringResource(R.string.settings_podcasts), onBack, modifier) {
        item(key = "sources") { SectionHeader(stringResource(R.string.settings_section_sources)) }
        item(key = "local-podcasts") {
            SwitchRow(
                title = stringResource(R.string.settings_local_podcasts),
                summary = stringResource(R.string.settings_local_podcasts_summary),
                icon = DesignR.drawable.ic_playlist_play,
                checked = state.useLocalPodcasts,
                onCheckedChange = { enabled ->
                    if (enabled) requestAudioPermission() else viewModel.setUseLocalPodcasts(false)
                },
            )
        }

        item(key = "refresh") { SectionHeader(stringResource(R.string.settings_section_refresh)) }
        item(key = "podcast-refresh") {
            ClickRow(
                title = stringResource(R.string.settings_podcast_refresh),
                value = podcastRefreshLabel(state.podcastRefreshHours),
                icon = DesignR.drawable.ic_sync,
                onClick = { choosingRefresh = true },
            )
        }
        item(key = "refresh-podcasts") { RefreshRow(stringResource(R.string.settings_refresh_podcasts), viewModel::refreshPodcasts) }

        item(key = "opml") { SectionHeader(stringResource(R.string.settings_section_opml)) }
        item(key = "opml-import") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_opml_import)) },
                supportingContent = { Text(stringResource(R.string.settings_opml_import_summary)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                modifier = Modifier.clickable { importLauncher.launch(arrayOf("*/*")) },
            )
        }
        item(key = "db-import") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_db_import)) },
                supportingContent = { Text(stringResource(R.string.settings_db_import_summary)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                modifier = Modifier.clickable { backupLauncher.launch(arrayOf("*/*")) },
            )
        }
        item(key = "db-export") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_db_export)) },
                supportingContent = { Text(stringResource(R.string.settings_db_export_summary)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
                modifier = Modifier.clickable { databaseExportLauncher.launch("kleinaberlaut-podcasts.db") },
            )
        }
        item(key = "opml-export") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_opml_export)) },
                supportingContent = { Text(stringResource(R.string.settings_opml_export_summary)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
                modifier = Modifier.clickable { exportLauncher.launch("kleinaberlaut-podcasts.opml") },
            )
        }

        item(key = "playback") { SectionHeader(stringResource(R.string.settings_playback)) }
        item(key = "podcast-auto-play-next") {
            SwitchRow(
                title = stringResource(R.string.settings_podcast_auto_play_next),
                summary = stringResource(R.string.settings_podcast_auto_play_next_summary),
                icon = DesignR.drawable.ic_playlist_play,
                checked = state.podcastAutoPlayNext,
                onCheckedChange = viewModel::setPodcastAutoPlayNext,
            )
        }
        item(key = "podcast-resume") {
            Column {
                ResumeRows(state.podcastResume, DesignR.drawable.ic_playlist_play, "podcast", viewModel::setPodcastResume)
            }
        }

        item(key = "downloads") { SectionHeader(stringResource(R.string.nav_downloads)) }
        item(key = "podcast-mobile") {
            SwitchRow(
                title = stringResource(R.string.settings_podcast_mobile),
                summary = stringResource(R.string.settings_podcast_mobile_summary),
                icon = DesignR.drawable.ic_cloud,
                checked = state.podcastMobileData,
                onCheckedChange = viewModel::setPodcastMobileData,
            )
        }
        item(key = "podcast-auto-count") {
            ClickRow(
                title = stringResource(R.string.settings_podcast_auto_count),
                value = pluralStringResource(
                    R.plurals.settings_podcast_auto_count_value,
                    state.podcastAutoDownloadCount,
                    state.podcastAutoDownloadCount,
                ),
                icon = DesignR.drawable.ic_download,
                onClick = { choosingAutoCount = true },
            )
        }
        item(key = "podcast-delete-heard") {
            ClickRow(
                title = stringResource(R.string.settings_podcast_delete_heard),
                value = podcastDeleteHeardLabel(state.podcastDeleteHeardDays),
                icon = DesignR.drawable.ic_delete,
                onClick = { choosingDeleteHeard = true },
            )
        }

        item(key = "stats") { SectionHeader(stringResource(R.string.settings_section_stats)) }
        item(key = "stats-reset") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stats_reset)) },
                supportingContent = { Text(stringResource(R.string.settings_stats_reset_summary)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                modifier = Modifier.clickable { confirmingStatsReset = true },
            )
        }
    }

    if (confirmingStatsReset) {
        AlertDialog(
            onDismissRequest = { confirmingStatsReset = false },
            title = { Text(stringResource(R.string.settings_stats_reset_title)) },
            text = { Text(stringResource(R.string.settings_stats_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetPodcastStats()
                    confirmingStatsReset = false
                }) { Text(stringResource(R.string.settings_stats_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingStatsReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    opmlToImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { opmlToImport = null },
            title = { Text(stringResource(R.string.settings_opml_import)) },
            text = { Text(stringResource(R.string.settings_opml_import_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importOpml(uri, replace = false)
                    opmlToImport = null
                }) { Text(stringResource(R.string.settings_opml_add)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { opmlToImport = null }) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        viewModel.importOpml(uri, replace = true)
                        opmlToImport = null
                    }) { Text(stringResource(R.string.settings_opml_replace)) }
                }
            },
        )
    }
    if (choosingRefresh) {
        ChoiceDialog(
            title = stringResource(R.string.settings_podcast_refresh),
            options = PodcastSettings.REFRESH_HOUR_CHOICES,
            selected = state.podcastRefreshHours,
            label = { podcastRefreshLabel(it) },
            onSelect = {
                viewModel.setPodcastRefreshHours(it)
                choosingRefresh = false
            },
            onDismiss = { choosingRefresh = false },
        )
    }
    if (choosingAutoCount) {
        ChoiceDialog(
            title = stringResource(R.string.settings_podcast_auto_count),
            options = PodcastSettings.AUTO_DOWNLOAD_COUNT_CHOICES,
            selected = state.podcastAutoDownloadCount,
            label = { pluralStringResource(R.plurals.settings_podcast_auto_count_value, it, it) },
            onSelect = {
                viewModel.setPodcastAutoDownloadCount(it)
                choosingAutoCount = false
            },
            onDismiss = { choosingAutoCount = false },
        )
    }
    if (choosingDeleteHeard) {
        ChoiceDialog(
            title = stringResource(R.string.settings_podcast_delete_heard),
            options = PodcastSettings.DELETE_HEARD_CHOICES,
            selected = state.podcastDeleteHeardDays,
            label = { podcastDeleteHeardLabel(it) },
            onSelect = {
                viewModel.setPodcastDeleteHeardDays(it)
                choosingDeleteHeard = false
            },
            onDismiss = { choosingDeleteHeard = false },
        )
    }
}

/** Bücher: Quellen (Gerät, Server) und Autorenbilder; Wiedergabe und Downloads haben noch keine Einstellungen. */
@Composable
fun AudiobookSettingsScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (serverId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingBookStatsReset by rememberSaveable { mutableStateOf(false) }
    // Einschalten erst mit Berechtigung, sonst bliebe der Schalter an und es erschiene nichts.
    val requestAudioPermission = rememberAudioPermissionRequester { granted ->
        if (granted) viewModel.setUseLocalBooks(true)
    }

    SettingsPage(stringResource(R.string.settings_audiobooks), onBack, modifier) {
        item(key = "sources") { SectionHeader(stringResource(R.string.settings_section_sources)) }
        item(key = "local-books") {
            SwitchRow(
                title = stringResource(R.string.settings_local_books),
                summary = stringResource(R.string.settings_local_books_summary),
                icon = DesignR.drawable.ic_book,
                checked = state.useLocalBooks,
                onCheckedChange = { enabled ->
                    if (enabled) requestAudioPermission() else viewModel.setUseLocalBooks(false)
                },
            )
        }
        item(key = "audiobookshelf") { AudiobookshelfSection(onAdd = onAddServer, onEdit = onEditServer) }

        item(key = "refresh") { SectionHeader(stringResource(R.string.settings_section_refresh)) }
        item(key = "refresh-audiobooks") { RefreshRow(stringResource(R.string.settings_refresh_audiobooks), viewModel::refreshAudiobooks) }

        item(key = "images") { SectionHeader(stringResource(R.string.settings_section_images)) }
        item(key = "author-images") {
            SwitchRow(
                title = stringResource(R.string.settings_author_images),
                summary = stringResource(R.string.settings_author_images_summary),
                icon = DesignR.drawable.ic_book,
                checked = state.authorImages,
                onCheckedChange = viewModel::setAuthorImages,
            )
        }

        item(key = "playback") { SectionHeader(stringResource(R.string.settings_playback)) }
        item(key = "book-resume") {
            Column {
                ResumeRows(state.bookResume, DesignR.drawable.ic_book, "book", viewModel::setBookResume)
            }
        }
        item(key = "chapter-restart") {
            SwitchRow(
                title = stringResource(R.string.settings_chapter_restart),
                summary = stringResource(R.string.settings_chapter_restart_summary),
                icon = DesignR.drawable.ic_book,
                checked = state.restartChapter,
                onCheckedChange = viewModel::setRestartChapter,
            )
        }

        item(key = "stats") { SectionHeader(stringResource(R.string.settings_section_stats)) }
        item(key = "stats-reset") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_book_stats_reset)) },
                supportingContent = { Text(stringResource(R.string.settings_book_stats_reset_summary)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                modifier = Modifier.clickable { confirmingBookStatsReset = true },
            )
        }
    }

    if (confirmingBookStatsReset) {
        AlertDialog(
            onDismissRequest = { confirmingBookStatsReset = false },
            title = { Text(stringResource(R.string.settings_stats_reset_title)) },
            text = { Text(stringResource(R.string.settings_book_stats_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetBookStats()
                    confirmingBookStatsReset = false
                }) { Text(stringResource(R.string.settings_stats_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingBookStatsReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
