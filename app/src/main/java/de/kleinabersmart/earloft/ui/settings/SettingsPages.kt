package de.kleinabersmart.earloft.ui.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import de.kleinabersmart.earloft.ui.components.launchOrHint
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.DEFAULT_RSS_BRIDGE
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.data.spoken.BookButtons
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.download.DownloadSettings
import de.kleinabersmart.earloft.core.model.MobileStreamQuality
import de.kleinabersmart.earloft.core.model.ReplayGainMode
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.permission.rememberAudioPermissionRequester
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.earloft.ui.spoken.AudiobookshelfSection
import de.kleinabersmart.earloft.ui.spoken.OpmlImportDialog
import de.kleinabersmart.earloft.ui.spoken.PodcastArt
import de.kleinabersmart.earloft.ui.spoken.feedProblemText
import de.kleinabersmart.earloft.ui.spoken.countryName
import de.kleinabersmart.earloft.ui.spoken.label
import de.kleinabersmart.earloft.ui.spoken.opmlImportMessage
import de.kleinabersmart.earloft.ui.spoken.summary
import de.kleinabersmart.earloft.ui.theme.ThemeMode
import java.time.LocalDate
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

// Music, podcasts and books are structured the same way: sources · refresh · images · playback · downloads.
// Sections without content are omitted (concept 6).

/** "Sync now" or similar as a row of the "Refresh" section. */
/** The two automatic options for albums in the download settings. */
private enum class AutoAlbums(val title: Int, val summary: Int) {
    NEWEST(R.string.settings_auto_download_newest_albums, R.string.settings_auto_download_newest_albums_summary),
    MOST_PLAYED(R.string.settings_auto_download_most_played_albums, R.string.settings_auto_download_most_played_albums_summary),
}

/** Custom number of albums via slider, like the custom duration of the sleep timer. */
@Composable
private fun CustomAlbumCount(initial: Int, onApply: (Int) -> Unit) {
    val step = DownloadSettings.AUTO_ALBUM_STEP
    val max = DownloadSettings.AUTO_ALBUM_MAX
    var count by remember { mutableFloatStateOf((if (initial > 0) initial else CUSTOM_ALBUMS_DEFAULT).coerceIn(step, max).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.settings_auto_download_albums_custom), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = count,
                // Snap to whole steps (the slider returns intermediate values).
                onValueChange = { count = (it / step).roundToInt() * step.toFloat() },
                valueRange = step.toFloat()..max.toFloat(),
                steps = max / step - 2,
                modifier = Modifier.weight(1f),
            )
            Text(pluralStringResource(R.plurals.album_count, count.roundToInt(), count.roundToInt()))
        }
        Button(onClick = { onApply(count.roundToInt()) }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.settings_auto_download_albums_apply))
        }
    }
}

private const val CUSTOM_ALBUMS_DEFAULT = 20

@Composable
private fun autoAlbumsLabel(count: Int): String =
    if (count == 0) stringResource(R.string.settings_auto_download_albums_off) else pluralStringResource(R.plurals.album_count, count, count)

@Composable
private fun RefreshRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** The messages of [viewModel]'s imports and exports as a snackbar (Settings → Podcasts and setup wizard). */
@Composable
fun TransferMessagesEffect(viewModel: SettingsViewModel) {
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
}

/** What applies to all areas: downloads, appearance, sync, backup, areas, setup. */
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: FullBackupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) backupViewModel.export(uri)
    }
    val importBackup = rememberFullBackupImport(backupViewModel)
    FullBackupMessagesEffect(backupViewModel)

    SettingsPage(stringResource(R.string.settings_general), onBack, modifier) {
        group("downloads", R.string.nav_downloads) {
            row("downloads-open") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.nav_downloads)) },
                    supportingContent = { Text(stringResource(R.string.settings_downloads_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenDownloads),
                )
            }
            row("ask-mobile-stream") {
                SwitchRow(
                    title = stringResource(R.string.settings_ask_mobile_stream),
                    summary = stringResource(R.string.settings_ask_mobile_stream_summary),
                    icon = DesignR.drawable.ic_cloud,
                    checked = state.askBeforeMobileStreaming,
                    onCheckedChange = viewModel::setAskBeforeMobileStreaming,
                )
            }
        }

        group("appearance", R.string.settings_section_appearance) {
            row("theme") {
                ClickRow(
                    title = stringResource(R.string.settings_theme),
                    value = stringResource(state.themeMode.label),
                    icon = DesignR.drawable.ic_contrast,
                    onClick = { choosingTheme = true },
                )
            }
            // Material You only exists from Android 12 on; before that the app colors always apply.
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
            // Off: the app colors as everywhere else, i.e. these when system colors are enabled.
            row("player-cover-colors") {
                SwitchRow(
                    title = stringResource(R.string.settings_player_cover_colors),
                    summary = stringResource(R.string.settings_player_cover_colors_summary),
                    icon = DesignR.drawable.ic_play,
                    checked = state.playerCoverColors,
                    onCheckedChange = viewModel::setPlayerCoverColors,
                )
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

        group("refresh", R.string.settings_section_refresh) {
            row("refresh-all") { RefreshRow(stringResource(R.string.settings_refresh_all), viewModel::refreshAll) }
        }

        group("backup", R.string.settings_section_backup) {
            row("backup-export") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_backup_export)) },
                    supportingContent = { Text(stringResource(R.string.settings_backup_export_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
                    modifier = Modifier.clickable { backupExportLauncher.launchOrHint("earloft-backup-${LocalDate.now()}.zip", context) },
                )
            }
            row("backup-import") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_backup_import)) },
                    supportingContent = { Text(stringResource(R.string.settings_backup_import_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = importBackup),
                )
            }
        }

        // Show and hide areas; the last enabled one can't be disabled.
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

        group("setup", R.string.settings_section_setup) {
            row("setup-again") {
                ClickRow(
                    title = stringResource(R.string.settings_setup_again),
                    value = stringResource(R.string.settings_setup_again_summary),
                    icon = DesignR.drawable.ic_restart,
                    onClick = onOpenSetup,
                )
            }
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
    var choosingMobileQuality by rememberSaveable { mutableStateOf(false) }
    var choosingAutoAlbums by rememberSaveable { mutableStateOf<AutoAlbums?>(null) }
    var choosingSyncInterval by rememberSaveable { mutableStateOf(false) }
    // Enable only with the permission; otherwise the permission page would come up again right away.
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
            row("mobile-quality") {
                ClickRow(
                    title = stringResource(R.string.settings_mobile_stream_quality),
                    value = stringResource(state.mobileStreamQuality.label),
                    icon = DesignR.drawable.ic_signal_cellular,
                    onClick = { choosingMobileQuality = true },
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
            row("endless-mix") {
                SwitchRow(
                    title = stringResource(R.string.settings_endless_mix),
                    summary = stringResource(R.string.settings_endless_mix_summary),
                    icon = DesignR.drawable.ic_auto_awesome,
                    checked = state.endlessMix,
                    onCheckedChange = viewModel::setEndlessMix,
                )
            }
        }

        group("downloads", R.string.nav_downloads) {
            // Applies to music; podcasts and books have their own switch. What is stored is the opposite ("Wi-Fi only").
            row("music-mobile") {
                SwitchRow(
                    title = stringResource(R.string.settings_music_mobile),
                    summary = stringResource(R.string.settings_music_mobile_summary),
                    icon = DesignR.drawable.ic_cloud,
                    checked = !state.downloadWifiOnly,
                    onCheckedChange = { viewModel.setDownloadWifiOnly(!it) },
                )
            }
            row("auto-favorites") {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_download_favorites),
                    summary = stringResource(R.string.settings_auto_download_favorites_summary),
                    icon = DesignR.drawable.ic_favorite,
                    checked = state.autoDownloadFavorites,
                    onCheckedChange = viewModel::setAutoDownloadFavorites,
                )
            }
            // Belongs to the favorites auto-download and only has an effect with it.
            row("auto-favorite-artists") {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_download_favorite_artists),
                    summary = stringResource(R.string.settings_auto_download_favorite_artists_summary),
                    icon = DesignR.drawable.ic_person,
                    checked = state.autoDownloadFavoriteArtists,
                    onCheckedChange = viewModel::setAutoDownloadFavoriteArtists,
                    enabled = state.autoDownloadFavorites,
                )
            }
            row("auto-newest-albums") {
                ClickRow(
                    title = stringResource(R.string.settings_auto_download_newest_albums),
                    value = autoAlbumsLabel(state.autoDownloadNewestAlbums),
                    icon = DesignR.drawable.ic_download,
                    onClick = { choosingAutoAlbums = AutoAlbums.NEWEST },
                )
            }
            row("auto-most-played-albums") {
                ClickRow(
                    title = stringResource(R.string.settings_auto_download_most_played_albums),
                    value = autoAlbumsLabel(state.autoDownloadMostPlayedAlbums),
                    icon = DesignR.drawable.ic_replay,
                    onClick = { choosingAutoAlbums = AutoAlbums.MOST_PLAYED },
                )
            }
        }

        // What sends names to internet services: images and lyrics.
        group("online", R.string.settings_section_online) {
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
            row("online-lyrics") {
                SwitchRow(
                    title = stringResource(R.string.settings_online_lyrics),
                    summary = stringResource(R.string.settings_online_lyrics_summary),
                    icon = DesignR.drawable.ic_lyrics,
                    checked = state.onlineLyrics,
                    onCheckedChange = viewModel::setOnlineLyrics,
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

    choosingAutoAlbums?.let { rule ->
        val current = if (rule == AutoAlbums.NEWEST) state.autoDownloadNewestAlbums else state.autoDownloadMostPlayedAlbums
        val choose: (Int) -> Unit = {
            if (rule == AutoAlbums.NEWEST) viewModel.setAutoDownloadNewestAlbums(it) else viewModel.setAutoDownloadMostPlayedAlbums(it)
            choosingAutoAlbums = null
        }
        ChoiceDialog(
            title = stringResource(rule.title),
            // A custom number appears as a separate row so the selection stays visible.
            options = (DownloadSettings.AUTO_ALBUM_CHOICES + current).distinct().sorted(),
            selected = current,
            label = { autoAlbumsLabel(it) },
            description = stringResource(rule.summary),
            onSelect = choose,
            onDismiss = { choosingAutoAlbums = null },
            footer = { CustomAlbumCount(initial = current, onApply = choose) },
        )
    }

    if (choosingMobileQuality) {
        ChoiceDialog(
            title = stringResource(R.string.settings_mobile_stream_quality),
            options = MobileStreamQuality.entries,
            selected = state.mobileStreamQuality,
            label = { stringResource(it.label) },
            summary = { if (it == MobileStreamQuality.ORIGINAL) null else stringResource(R.string.stream_quality_transcoded_summary) },
            onSelect = {
                viewModel.setMobileStreamQuality(it)
                choosingMobileQuality = false
            },
            onDismiss = { choosingMobileQuality = false },
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

/**
 * Podcasts have no servers (the feeds live in the library); the only source is the folder on the device. Subscriptions whose
 * sync fails are listed at the very top; a tap opens their page ([onOpenFeed]).
 */
@Composable
fun PodcastsSettingsScreen(
    onBack: () -> Unit,
    onOpenFeed: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingRefresh by rememberSaveable { mutableStateOf(false) }
    var choosingDeleteHeard by rememberSaveable { mutableStateOf(false) }
    var choosingAutoCount by rememberSaveable { mutableStateOf(false) }
    var confirmingStatsReset by rememberSaveable { mutableStateOf(false) }
    var choosingCountry by rememberSaveable { mutableStateOf(false) }
    var editingBridge by rememberSaveable { mutableStateOf(false) }
    var feedToReactivate by rememberSaveable { mutableStateOf<String?>(null) }
    var feedToDelete by rememberSaveable { mutableStateOf<String?>(null) }
    // Enable only with the permission, otherwise the switch would stay on and nothing would appear.
    val requestAudioPermission = rememberAudioPermissionRequester { granted ->
        if (granted) viewModel.setUseLocalPodcasts(true)
    }
    // On import a dialog asks whether the subscriptions are added or replace the existing ones.
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
    TransferMessagesEffect(viewModel)

    // The problematic subscriptions only arrive with the database; otherwise the list would keep the row that was first until then anchored, and
    // they would sit above the visible start.
    val listState = rememberLazyListState()
    val hasProblems = state.problemPodcasts.isNotEmpty()
    LaunchedEffect(hasProblems) {
        if (hasProblems && listState.firstVisibleItemScrollOffset == 0) listState.scrollToItem(0)
    }
    SettingsPage(stringResource(R.string.settings_podcasts), onBack, modifier, listState) {
        if (state.problemPodcasts.isNotEmpty()) {
            val count = state.problemPodcasts.size
            group("feed-problems", { stringResource(R.string.settings_section_feed_problems, count) }) {
                state.problemPodcasts.forEach { feed ->
                    row("problem-${feed.id}") {
                        ListItem(
                            headlineContent = { Text(feed.title) },
                            supportingContent = { Text(feedProblemText(feed), color = MaterialTheme.colorScheme.error) },
                            leadingContent = { PodcastArt(feed.imageUrl, Modifier.size(40.dp)) },
                            modifier = Modifier.clickable { onOpenFeed(feed.id) },
                        )
                    }
                }
            }
        }

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
                // The country belongs to Apple and is therefore right below its switch.
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
            row("podcast-effects") {
                Column {
                    EffectRows(state.podcastEffects, "podcast", viewModel::setPodcastEffects)
                }
            }
            row("sleep-shake") {
                SwitchRow(
                    title = stringResource(R.string.settings_sleep_shake),
                    summary = stringResource(R.string.settings_sleep_shake_summary),
                    icon = DesignR.drawable.ic_bedtime,
                    checked = state.shakeToExtend,
                    onCheckedChange = viewModel::setShakeToExtend,
                )
            }
        }

        // As with books: skipping and resuming as separate sections so "Playback" stays manageable.
        group("skip", R.string.settings_section_skip) {
            row("podcast-seek") {
                Column {
                    SeekRows(state.podcastSeek, "podcast", viewModel::setPodcastSeek)
                }
            }
        }

        group("resume", R.string.settings_section_resume) {
            row("podcast-resume") {
                Column {
                    ResumeRows(state.podcastResume, DesignR.drawable.ic_playlist_play, "podcast", viewModel::setPodcastResume)
                }
            }
        }

        group("episode-lists", R.string.settings_section_episode_lists) {
            row("podcast-swipe") {
                SwitchRow(
                    title = stringResource(R.string.settings_podcast_swipe),
                    summary = stringResource(R.string.settings_podcast_swipe_summary),
                    icon = DesignR.drawable.ic_list,
                    checked = state.podcastSwipeActions,
                    onCheckedChange = viewModel::setPodcastSwipeActions,
                )
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
            row("podcast-auto-new") {
                SwitchRow(
                    title = stringResource(R.string.settings_podcast_auto_new),
                    summary = stringResource(R.string.settings_podcast_auto_new_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = state.podcastAutoDownloadNew,
                    onCheckedChange = viewModel::setPodcastAutoDownloadNew,
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

        group("opml", R.string.settings_section_opml) {
            row("opml-import") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_opml_import)) },
                    supportingContent = { Text(stringResource(R.string.settings_opml_import_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable { importLauncher.launchOrHint(arrayOf("*/*"), context) },
                )
            }
            row("db-import") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_db_import)) },
                    supportingContent = { Text(stringResource(R.string.settings_db_import_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable { backupLauncher.launchOrHint(arrayOf("*/*"), context) },
                )
            }
            row("db-export") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_db_export)) },
                    supportingContent = { Text(stringResource(R.string.settings_db_export_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
                    modifier = Modifier.clickable { databaseExportLauncher.launchOrHint("earloft-podcasts.db", context) },
                )
            }
            row("opml-export") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_opml_export)) },
                    supportingContent = { Text(stringResource(R.string.settings_opml_export_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
                    modifier = Modifier.clickable { exportLauncher.launchOrHint("earloft-podcasts.opml", context) },
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
 * Address of the RSS bridge for ARD Sounds shows in the OPML export (concept 12.10). Empty disables it, "Default"
 * uses the public instance.
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

/**
 * Books: sources (device including folder access, servers), sync, playback, skipping, resuming, downloads, online services
 * (author images) and statistics.
 */
@Composable
fun AudiobookSettingsScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (serverId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingBookStatsReset by rememberSaveable { mutableStateOf(false) }
    var choosingBookDelete by rememberSaveable { mutableStateOf(false) }
    var choosingBookButtons by rememberSaveable { mutableStateOf(false) }
    // Enable only with the permission, otherwise the switch would stay on and nothing would appear.
    val requestAudioPermission = rememberAudioPermissionRequester { granted ->
        if (granted) viewModel.setUseLocalBooks(true)
    }
    // Access to the "Audiobooks" folder for covers and texts of the book folders (books_improvements M7).
    val pickBookFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let(viewModel::shareLocalBooksFolder)
    }
    val snackbars = LocalSnackbars.current
    val folderRejectedText = stringResource(R.string.settings_local_books_folder_wrong)
    LaunchedEffect(Unit) { viewModel.folderRejected.collect { snackbars.show(folderRejectedText, SnackbarDuration.Long) } }

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
            if (state.useLocalBooks) {
                row("local-books-folder") {
                    SwitchRow(
                        title = stringResource(R.string.settings_local_books_folder),
                        summary = stringResource(R.string.settings_local_books_folder_summary),
                        icon = DesignR.drawable.ic_folder,
                        checked = state.localBooksFolderShared,
                        onCheckedChange = { share ->
                            if (share) pickBookFolder.launchOrHint(viewModel.localBooksFolderStart, context) else viewModel.unshareLocalBooksFolder()
                        },
                    )
                }
            }
            row("audiobookshelf") { AudiobookshelfSection(onAdd = onAddServer, onEdit = onEditServer) }
            row("ard-sounds-books") {
                SwitchRow(
                    title = stringResource(R.string.directory_ard_sounds),
                    summary = stringResource(R.string.settings_ard_sounds_books_summary),
                    icon = DesignR.drawable.ic_cloud,
                    checked = state.ardSoundsBooks,
                    onCheckedChange = viewModel::setArdSoundsBooks,
                )
            }
        }

        group("refresh", R.string.settings_section_refresh) {
            row("refresh-audiobooks") { RefreshRow(stringResource(R.string.settings_refresh_audiobooks), viewModel::refreshAudiobooks) }
        }

        group("playback", R.string.settings_playback) {
            row("book-effects") {
                Column {
                    EffectRows(state.bookEffects, "book", viewModel::setBookEffects)
                }
            }
            row("sleep-shake") {
                SwitchRow(
                    title = stringResource(R.string.settings_sleep_shake),
                    summary = stringResource(R.string.settings_sleep_shake_summary),
                    icon = DesignR.drawable.ic_bedtime,
                    checked = state.shakeToExtend,
                    onCheckedChange = viewModel::setShakeToExtend,
                )
            }
        }

        // With ten rows "Playback" got longer than a screen: skipping and resuming therefore stand on their own.
        group("skip", R.string.settings_section_skip) {
            row("book-seek") {
                Column {
                    SeekRows(state.bookSeek, "book", viewModel::setBookSeek)
                }
            }
            row("book-buttons") {
                ClickRow(
                    title = stringResource(R.string.settings_book_buttons),
                    value = bookButtonsLabel(state.bookButtons, state.bookSeek),
                    icon = DesignR.drawable.ic_skip_next,
                    onClick = { choosingBookButtons = true },
                )
            }
        }

        group("resume", R.string.settings_section_resume) {
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
            row("book-auto-started") {
                SwitchRow(
                    title = stringResource(R.string.settings_book_auto_started),
                    summary = stringResource(R.string.settings_book_auto_started_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = state.bookAutoStarted,
                    onCheckedChange = viewModel::setBookAutoStarted,
                )
            }
            row("book-auto-next") {
                SwitchRow(
                    title = stringResource(R.string.settings_book_auto_next),
                    summary = stringResource(R.string.settings_book_auto_next_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = state.bookAutoNextInSeries,
                    onCheckedChange = viewModel::setBookAutoNextInSeries,
                )
            }
            if (state.ardSoundsBooks) {
                row("book-load-expiring") {
                    SwitchRow(
                        title = stringResource(R.string.settings_book_load_expiring),
                        summary = stringResource(R.string.settings_book_load_expiring_summary),
                        icon = DesignR.drawable.ic_download,
                        checked = state.bookLoadExpiring,
                        onCheckedChange = viewModel::setBookLoadExpiring,
                    )
                }
            }
            row("book-delete-finished") {
                ClickRow(
                    title = stringResource(R.string.settings_book_delete_finished),
                    value = podcastDeleteHeardLabel(state.bookDeleteFinishedDays),
                    icon = DesignR.drawable.ic_delete,
                    onClick = { choosingBookDelete = true },
                )
            }
        }

        group("online", R.string.settings_section_online) {
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

    if (choosingBookButtons) {
        ChoiceDialog(
            title = stringResource(R.string.settings_book_buttons),
            options = BookButtons.entries,
            selected = state.bookButtons,
            label = { bookButtonsLabel(it, state.bookSeek) },
            description = stringResource(R.string.settings_book_buttons_summary),
            onSelect = {
                viewModel.setBookButtons(it)
                choosingBookButtons = false
            },
            onDismiss = { choosingBookButtons = false },
        )
    }

    if (choosingBookDelete) {
        ChoiceDialog(
            title = stringResource(R.string.settings_book_delete_finished),
            options = PodcastSettings.DELETE_HEARD_CHOICES,
            selected = state.bookDeleteFinishedDays,
            label = { podcastDeleteHeardLabel(it) },
            onSelect = {
                viewModel.setBookDeleteFinishedDays(it)
                choosingBookDelete = false
            },
            onDismiss = { choosingBookDelete = false },
        )
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

/** "Skip (−10 s / +30 s)" or "Previous and next chapter". */
@Composable
private fun bookButtonsLabel(buttons: BookButtons, seek: SeekSteps): String = when (buttons) {
    BookButtons.SEEK -> stringResource(R.string.settings_book_buttons_seek, seek.backSeconds, seek.forwardSeconds)
    BookButtons.CHAPTERS -> stringResource(R.string.settings_book_buttons_chapters)
}
