package de.kleinabersmart.tinyamp.ui.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import de.kleinabersmart.tinyamp.core.network.podcast.ardsounds.DEFAULT_RSS_BRIDGE
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.model.SpokenMode
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastSettings
import de.kleinabersmart.tinyamp.core.data.sync.LibrarySettings
import de.kleinabersmart.tinyamp.core.model.ReplayGainMode
import de.kleinabersmart.tinyamp.ui.components.EmptyHint
import de.kleinabersmart.tinyamp.ui.components.LoadFailedHint
import de.kleinabersmart.tinyamp.ui.components.GroupMargin
import de.kleinabersmart.tinyamp.ui.components.group
import de.kleinabersmart.tinyamp.ui.permission.rememberAudioPermissionRequester
import de.kleinabersmart.tinyamp.ui.snackbar.LocalSnackbars
import de.kleinabersmart.tinyamp.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.tinyamp.ui.spoken.AudiobookshelfSection
import de.kleinabersmart.tinyamp.ui.spoken.OpmlImportDialog
import de.kleinabersmart.tinyamp.ui.spoken.PodcastArt
import de.kleinabersmart.tinyamp.ui.spoken.countryName
import de.kleinabersmart.tinyamp.ui.spoken.label
import de.kleinabersmart.tinyamp.ui.spoken.opmlImportMessage
import de.kleinabersmart.tinyamp.ui.spoken.summary
import de.kleinabersmart.tinyamp.ui.theme.ThemeMode
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

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
        group("appearance", R.string.settings_section_appearance) {
            row("theme") {
                ClickRow(
                    title = stringResource(R.string.settings_theme),
                    value = stringResource(state.themeMode.label),
                    icon = DesignR.drawable.ic_contrast,
                    onClick = { choosingTheme = true },
                )
            }
            // Material You gibt es erst ab Android 12; davor gelten immer die App-Farben.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                row("system-colors") {
                    SwitchRow(
                        title = stringResource(R.string.settings_system_colors),
                        summary = stringResource(R.string.settings_system_colors_summary),
                        icon = DesignR.drawable.ic_palette,
                        checked = state.systemColors,
                        onCheckedChange = viewModel::setSystemColors,
                    )
                }
            }
            row("always-nav") {
                SwitchRow(
                    title = stringResource(R.string.settings_always_navigation_bar),
                    summary = stringResource(R.string.settings_always_navigation_bar_summary),
                    icon = R.drawable.ic_nav_home,
                    checked = state.alwaysShowNavigationBar,
                    onCheckedChange = viewModel::setAlwaysShowNavigationBar,
                )
            }
        }

        // Bereiche ein- und ausblenden; der letzte eingeschaltete lässt sich nicht ausschalten.
        group("areas", R.string.settings_section_areas) {
            SpokenMode.entries.forEach { area ->
                row("area-${area.name}") {
                    val enabled = area in state.areas
                    SwitchRow(
                        title = stringResource(areaTitle(area)),
                        summary = stringResource(areaSummary(area)),
                        icon = areaIcon(area),
                        checked = enabled,
                        onCheckedChange = { viewModel.setAreaEnabled(area, it) },
                        enabled = !enabled || state.areas.size > 1,
                    )
                }
            }
        }
        item(key = "areas-hint") {
            Text(
                stringResource(R.string.settings_areas_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        group("downloads", R.string.nav_downloads) {
            row("wifi-only") {
                SwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    summary = stringResource(R.string.settings_wifi_only_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = state.downloadWifiOnly,
                    onCheckedChange = viewModel::setDownloadWifiOnly,
                )
            }
            row("downloads-open") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.nav_downloads)) },
                    supportingContent = { Text(stringResource(R.string.settings_downloads_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenDownloads),
                )
            }
        }

        group("refresh", R.string.settings_section_refresh) {
            row("refresh-all") { RefreshRow(stringResource(R.string.settings_refresh_all), viewModel::refreshAll) }
        }
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
        group("sources", R.string.settings_section_sources) {
            row("local-media") {
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
                row("servers-failed") { LoadFailedHint() }
            } else if (!state.isLoading && state.servers.isEmpty()) {
                row("no-servers") { EmptyHint(stringResource(R.string.settings_no_servers)) }
            }
            state.servers.forEach { item ->
                row(item.server.sourceId) {
                    ServerRow(
                        item = item,
                        onEdit = { onEditServer(item.server.sourceId) },
                        onSyncNow = { viewModel.syncNow(item.server.sourceId) },
                        onEnabledChange = { viewModel.setServerEnabled(item.server.sourceId, it) },
                        onRemove = { serverToRemove = item.server.sourceId },
                    )
                }
            }
        }
        item(key = "add") {
            FilledTonalButton(onClick = onAddServer, modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp)) {
                Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.settings_add_server), modifier = Modifier.padding(start = 8.dp))
            }
        }

        group("refresh", R.string.settings_section_refresh) {
            row("sync-interval") {
                ClickRow(
                    title = stringResource(R.string.settings_music_sync_interval),
                    value = syncIntervalLabel(state.syncIntervalDays),
                    icon = DesignR.drawable.ic_sync,
                    onClick = { choosingSyncInterval = true },
                )
            }
            row("refresh-music") { RefreshRow(stringResource(R.string.settings_refresh_music), viewModel::refreshMusic) }
        }

        group("images", R.string.settings_section_images) {
            row("artist-images") {
                SwitchRow(
                    title = stringResource(R.string.settings_artist_images),
                    summary = stringResource(R.string.settings_artist_images_summary),
                    icon = DesignR.drawable.ic_person,
                    checked = state.artistImages,
                    onCheckedChange = viewModel::setArtistImages,
                )
            }
            row("album-covers") {
                SwitchRow(
                    title = stringResource(R.string.settings_album_covers),
                    summary = stringResource(R.string.settings_album_covers_summary),
                    icon = DesignR.drawable.ic_music_note,
                    checked = state.albumCovers,
                    onCheckedChange = viewModel::setAlbumCovers,
                )
            }
        }

        group("playback", R.string.settings_playback) {
            row("replay-gain") {
                ClickRow(
                    title = stringResource(R.string.settings_replay_gain),
                    value = stringResource(state.replayGainMode.label),
                    icon = DesignR.drawable.ic_volume_up,
                    onClick = { choosingReplayGain = true },
                )
            }
            row("pre-amp") {
                PreAmpRow(
                    preAmpDb = state.preAmpDb,
                    enabled = state.replayGainMode != ReplayGainMode.OFF,
                    onPreAmpChange = viewModel::setPreAmpDb,
                )
            }
            row("scrobble") {
                SwitchRow(
                    title = stringResource(R.string.settings_scrobble),
                    summary = stringResource(R.string.settings_scrobble_summary),
                    icon = DesignR.drawable.ic_cloud,
                    checked = state.scrobble,
                    onCheckedChange = viewModel::setScrobble,
                )
            }
        }

        group("downloads", R.string.nav_downloads) {
            row("auto-favorites") {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_download_favorites),
                    summary = stringResource(R.string.settings_auto_download_favorites_summary),
                    icon = DesignR.drawable.ic_favorite,
                    checked = state.autoDownloadFavorites,
                    onCheckedChange = viewModel::setAutoDownloadFavorites,
                )
            }
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
    var choosingCountry by rememberSaveable { mutableStateOf(false) }
    var editingBridge by rememberSaveable { mutableStateOf(false) }
    var feedToReactivate by rememberSaveable { mutableStateOf<String?>(null) }
    var feedToDelete by rememberSaveable { mutableStateOf<String?>(null) }
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
                is TransferMessage.Imported -> opmlImportMessage(resources, message.result)
                TransferMessage.ImportFailed -> opmlImportMessage(resources, null)
                TransferMessage.Exported -> resources.getString(R.string.settings_opml_exported)
                TransferMessage.ExportFailed -> resources.getString(R.string.settings_opml_export_failed)
                TransferMessage.DatabaseImportStarted -> resources.getString(R.string.settings_db_import_started)
                is TransferMessage.DatabaseImported -> resources.getString(
                    R.string.settings_db_import_imported,
                    resources.getQuantityString(R.plurals.count_podcasts, message.result.feedsAdded, message.result.feedsAdded),
                    resources.getQuantityString(R.plurals.count_episodes, message.result.progressApplied, message.result.progressApplied),
                    message.result.progressKept + message.result.progressMissing,
                    resources.getQuantityString(R.plurals.count_episodes, message.result.statsImported, message.result.statsImported),
                )
                is TransferMessage.DatabaseExported -> databaseExportMessage(resources, message.result)
                TransferMessage.DatabaseExportFailed -> databaseExportMessage(resources, null)
                TransferMessage.StatsReset -> resources.getString(R.string.settings_stats_reset_done)
                TransferMessage.DatabaseImportFailed -> resources.getString(R.string.settings_db_import_failed)
            }
            snackbars.show(text, SnackbarDuration.Long)
        }
    }

    SettingsPage(stringResource(R.string.settings_podcasts), onBack, modifier) {
        group("sources", R.string.settings_section_sources) {
            row("local-podcasts") {
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
        }

        group("directories", R.string.settings_section_directories) {
            state.podcastDirectories.forEach { directory ->
                row("directory-${directory.name}") {
                    SwitchRow(
                        title = stringResource(directory.label),
                        summary = stringResource(directory.summary),
                        icon = R.drawable.ic_nav_search,
                        checked = directory in state.enabledPodcastDirectories,
                        onCheckedChange = { viewModel.setPodcastDirectory(directory, it) },
                    )
                }
                // Das Land gehört zu Apple und steht deshalb direkt unter seinem Schalter.
                if (directory == PodcastDirectory.APPLE && directory in state.enabledPodcastDirectories) {
                    row("apple-country") {
                        ClickRow(
                            title = stringResource(R.string.settings_directory_apple_country),
                            value = countryName(state.appleCountry),
                            icon = R.drawable.ic_nav_search,
                            onClick = { choosingCountry = true },
                        )
                    }
                }
            }
        }

        group("refresh", R.string.settings_section_refresh) {
            row("podcast-refresh") {
                ClickRow(
                    title = stringResource(R.string.settings_podcast_refresh),
                    value = podcastRefreshLabel(state.podcastRefreshHours),
                    icon = DesignR.drawable.ic_sync,
                    onClick = { choosingRefresh = true },
                )
            }
            row("refresh-podcasts") { RefreshRow(stringResource(R.string.settings_refresh_podcasts), viewModel::refreshPodcasts) }
        }

        group("opml", R.string.settings_section_opml) {
            row("opml-import") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_opml_import)) },
                    supportingContent = { Text(stringResource(R.string.settings_opml_import_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable { importLauncher.launch(arrayOf("*/*")) },
                )
            }
            row("db-import") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_db_import)) },
                    supportingContent = { Text(stringResource(R.string.settings_db_import_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable { backupLauncher.launch(arrayOf("*/*")) },
                )
            }
            row("db-export") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_db_export)) },
                    supportingContent = { Text(stringResource(R.string.settings_db_export_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
                    modifier = Modifier.clickable { databaseExportLauncher.launch("tinyamp-podcasts.db") },
                )
            }
            row("opml-export") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_opml_export)) },
                    supportingContent = { Text(stringResource(R.string.settings_opml_export_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
                    modifier = Modifier.clickable { exportLauncher.launch("tinyamp-podcasts.opml") },
                )
            }
            row("rss-bridge") {
                ClickRow(
                    title = stringResource(R.string.settings_rss_bridge),
                    value = state.rssBridge.ifBlank { stringResource(R.string.settings_rss_bridge_off) },
                    icon = DesignR.drawable.ic_cloud,
                    onClick = { editingBridge = true },
                )
            }
        }

        group("playback", R.string.settings_playback) {
            row("podcast-auto-play-next") {
                SwitchRow(
                    title = stringResource(R.string.settings_podcast_auto_play_next),
                    summary = stringResource(R.string.settings_podcast_auto_play_next_summary),
                    icon = DesignR.drawable.ic_playlist_play,
                    checked = state.podcastAutoPlayNext,
                    onCheckedChange = viewModel::setPodcastAutoPlayNext,
                )
            }
            row("podcast-seek") {
                Column {
                    SeekRows(state.podcastSeek, "podcast", viewModel::setPodcastSeek)
                }
            }
            row("podcast-resume") {
                Column {
                    ResumeRows(state.podcastResume, DesignR.drawable.ic_playlist_play, "podcast", viewModel::setPodcastResume)
                }
            }
        }

        group("downloads", R.string.nav_downloads) {
            row("podcast-mobile") {
                SwitchRow(
                    title = stringResource(R.string.settings_podcast_mobile),
                    summary = stringResource(R.string.settings_podcast_mobile_summary),
                    icon = DesignR.drawable.ic_cloud,
                    checked = state.podcastMobileData,
                    onCheckedChange = viewModel::setPodcastMobileData,
                )
            }
            row("podcast-auto-count") {
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
            row("podcast-delete-heard") {
                ClickRow(
                    title = stringResource(R.string.settings_podcast_delete_heard),
                    value = podcastDeleteHeardLabel(state.podcastDeleteHeardDays),
                    icon = DesignR.drawable.ic_delete,
                    onClick = { choosingDeleteHeard = true },
                )
            }
        }

        group("stats", R.string.settings_section_stats) {
            row("stats-reset") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_stats_reset)) },
                    supportingContent = { Text(stringResource(R.string.settings_stats_reset_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                    modifier = Modifier.clickable { confirmingStatsReset = true },
                )
            }
        }

        if (state.archivedPodcasts.isNotEmpty()) {
            group("archived", R.string.settings_section_archived) {
                state.archivedPodcasts.forEach { feed ->
                    row("archived-${feed.id}") {
                        ListItem(
                            headlineContent = { Text(feed.title) },
                            supportingContent = { Text(stringResource(R.string.settings_archived_summary)) },
                            leadingContent = { PodcastArt(feed.imageUrl, Modifier.size(40.dp)) },
                            trailingContent = {
                                IconButton(onClick = { feedToDelete = feed.id }) {
                                    Icon(
                                        painterResource(DesignR.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.settings_archived_delete),
                                    )
                                }
                            },
                            modifier = Modifier.clickable { feedToReactivate = feed.id },
                        )
                    }
                }
            }
        }
    }

    state.archivedPodcasts.firstOrNull { it.id == feedToReactivate }?.let { feed ->
        val reactivatedText = stringResource(R.string.podcast_reactivated, feed.title)
        AlertDialog(
            onDismissRequest = { feedToReactivate = null },
            title = { Text(stringResource(R.string.podcast_reactivate_title)) },
            text = { Text(stringResource(R.string.podcast_reactivate_message, feed.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reactivatePodcast(feed.id)
                    snackbars.show(reactivatedText)
                    feedToReactivate = null
                }) { Text(stringResource(R.string.podcast_reactivate)) }
            },
            dismissButton = {
                TextButton(onClick = { feedToReactivate = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    state.archivedPodcasts.firstOrNull { it.id == feedToDelete }?.let { feed ->
        AlertDialog(
            onDismissRequest = { feedToDelete = null },
            title = { Text(stringResource(R.string.podcast_delete_archived_title)) },
            text = { Text(stringResource(R.string.podcast_delete_archived_message, feed.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteArchivedPodcast(feed.id)
                    feedToDelete = null
                }) { Text(stringResource(R.string.podcast_unsubscribe), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { feedToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
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
        OpmlImportDialog(
            onImport = { replace ->
                viewModel.importOpml(uri, replace)
                opmlToImport = null
            },
            onDismiss = { opmlToImport = null },
        )
    }
    if (editingBridge) {
        RssBridgeDialog(
            current = state.rssBridge,
            onSave = {
                viewModel.setRssBridge(it)
                editingBridge = false
            },
            onDismiss = { editingBridge = false },
        )
    }
    if (choosingCountry) {
        val choices = remember(state.appleCountry) {
            (PodcastSettings.APPLE_COUNTRY_CHOICES + state.appleCountry).distinct()
                .sortedBy { countryName(it) }
        }
        ChoiceDialog(
            title = stringResource(R.string.settings_directory_apple_country),
            options = choices,
            selected = state.appleCountry,
            label = { countryName(it) },
            onSelect = {
                viewModel.setAppleCountry(it)
                choosingCountry = false
            },
            onDismiss = { choosingCountry = false },
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

/**
 * Adresse der RSS-Bridge für Sendungen aus ARD Sounds im OPML-Export (Konzept 12.10). Leer schaltet sie ab, „Standard“
 * setzt die öffentliche Instanz ein.
 */
@Composable
private fun RssBridgeDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_rss_bridge)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_rss_bridge_message), modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.settings_rss_bridge_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { url = DEFAULT_RSS_BRIDGE }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.settings_rss_bridge_default))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url) }) { Text(stringResource(R.string.settings_rss_bridge_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Bücher: Quellen (Gerät, Server), Abgleich, Autorenbilder, Wiedergabe, Downloads (Mobilfunk) und Statistik. */
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
        group("sources", R.string.settings_section_sources) {
            row("local-books") {
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
            row("audiobookshelf") { AudiobookshelfSection(onAdd = onAddServer, onEdit = onEditServer) }
        }

        group("refresh", R.string.settings_section_refresh) {
            row("refresh-audiobooks") { RefreshRow(stringResource(R.string.settings_refresh_audiobooks), viewModel::refreshAudiobooks) }
        }

        group("images", R.string.settings_section_images) {
            row("author-images") {
                SwitchRow(
                    title = stringResource(R.string.settings_author_images),
                    summary = stringResource(R.string.settings_author_images_summary),
                    icon = DesignR.drawable.ic_book,
                    checked = state.authorImages,
                    onCheckedChange = viewModel::setAuthorImages,
                )
            }
        }

        group("playback", R.string.settings_playback) {
            row("book-seek") {
                Column {
                    SeekRows(state.bookSeek, "book", viewModel::setBookSeek)
                }
            }
            row("book-resume") {
                Column {
                    ResumeRows(state.bookResume, DesignR.drawable.ic_book, "book", viewModel::setBookResume)
                }
            }
            row("chapter-restart") {
                SwitchRow(
                    title = stringResource(R.string.settings_chapter_restart),
                    summary = stringResource(R.string.settings_chapter_restart_summary),
                    icon = DesignR.drawable.ic_book,
                    checked = state.restartChapter,
                    onCheckedChange = viewModel::setRestartChapter,
                )
            }
        }

        group("downloads", R.string.nav_downloads) {
            row("book-mobile") {
                SwitchRow(
                    title = stringResource(R.string.settings_book_mobile),
                    summary = stringResource(R.string.settings_book_mobile_summary),
                    icon = DesignR.drawable.ic_cloud,
                    checked = state.bookMobileData,
                    onCheckedChange = viewModel::setBookMobileData,
                )
            }
        }

        group("stats", R.string.settings_section_stats) {
            row("stats-reset") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_book_stats_reset)) },
                    supportingContent = { Text(stringResource(R.string.settings_book_stats_reset_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                    modifier = Modifier.clickable { confirmingBookStatsReset = true },
                )
            }
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
