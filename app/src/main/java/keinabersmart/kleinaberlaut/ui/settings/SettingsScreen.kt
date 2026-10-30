package keinabersmart.kleinaberlaut.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.ui.components.readableWidth
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.core.model.ServerInfo
import keinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.LocalWatchController
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.permission.rememberAudioPermissionRequester
import keinabersmart.kleinaberlaut.ui.theme.ThemeMode
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun SettingsScreen(
    onAddServer: () -> Unit,
    onEditServer: (sourceId: String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Einschalten erst mit Berechtigung; sonst stünde gleich wieder die Berechtigungsseite davor.
    val requestAudioPermission = rememberAudioPermissionRequester { granted ->
        if (granted) viewModel.setUseLocalMedia(true)
    }
    SettingsContent(
        state = state,
        watchAvailable = LocalWatchController.current?.isAvailable == true,
        onThemeModeChange = viewModel::setThemeMode,
        onArtistImagesChange = viewModel::setArtistImages,
        onAlbumCoversChange = viewModel::setAlbumCovers,
        onUseLocalMediaChange = { enabled ->
            if (enabled) requestAudioPermission() else viewModel.setUseLocalMedia(false)
        },
        onAddServer = onAddServer,
        onEditServer = onEditServer,
        onOpenDownloads = onOpenDownloads,
        onOpenWatch = onOpenWatch,
        onOpenAbout = onOpenAbout,
        onOpenLicenses = onOpenLicenses,
        onSyncNow = viewModel::syncNow,
        onRemove = viewModel::remove,
        onRefreshAll = viewModel::refreshAll,
        onWifiOnlyChange = viewModel::setDownloadWifiOnly,
        onAutoDownloadFavoritesChange = viewModel::setAutoDownloadFavorites,
        onScrobbleChange = viewModel::setScrobble,
        onReplayGainModeChange = viewModel::setReplayGainMode,
        onPreAmpChange = viewModel::setPreAmpDb,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: SettingsUiState,
    watchAvailable: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onArtistImagesChange: (Boolean) -> Unit,
    onAlbumCoversChange: (Boolean) -> Unit,
    onUseLocalMediaChange: (Boolean) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenWatch: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLicenses: () -> Unit,
    onSyncNow: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onAutoDownloadFavoritesChange: (Boolean) -> Unit,
    onScrobbleChange: (Boolean) -> Unit,
    onReplayGainModeChange: (ReplayGainMode) -> Unit,
    onPreAmpChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    var choosingReplayGain by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
        )
        if (state.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.navigationBarsPadding()) {
            // --- Allgemein ---
            item(key = "general") { SectionHeader(stringResource(R.string.settings_general)) }
            item(key = "theme") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_theme)) },
                    supportingContent = { Text(stringResource(state.themeMode.label)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_contrast), contentDescription = null) },
                    modifier = Modifier.clickable { choosingTheme = true },
                )
            }
            item(key = "artist-images") {
                SwitchRow(
                    title = stringResource(R.string.settings_artist_images),
                    summary = stringResource(R.string.settings_artist_images_summary),
                    icon = DesignR.drawable.ic_person,
                    checked = state.artistImages,
                    onCheckedChange = onArtistImagesChange,
                )
            }
            item(key = "album-covers") {
                SwitchRow(
                    title = stringResource(R.string.settings_album_covers),
                    summary = stringResource(R.string.settings_album_covers_summary),
                    icon = DesignR.drawable.ic_music_note,
                    checked = state.albumCovers,
                    onCheckedChange = onAlbumCoversChange,
                )
            }

            // --- Server ---
            item(key = "servers") { SectionHeader(stringResource(R.string.settings_servers)) }
            item(key = "local-media") {
                SwitchRow(
                    title = stringResource(R.string.settings_local_media),
                    summary = stringResource(R.string.settings_local_media_summary),
                    icon = DesignR.drawable.ic_music_note,
                    checked = state.useLocalMedia,
                    onCheckedChange = onUseLocalMediaChange,
                )
            }
            if (state.servers.isEmpty()) {
                item(key = "no-servers") { EmptyHint(stringResource(R.string.settings_no_servers)) }
            }
            items(state.servers, key = { it.server.sourceId }) { item ->
                ServerRow(
                    item = item,
                    onEdit = { onEditServer(item.server.sourceId) },
                    onSyncNow = { onSyncNow(item.server.sourceId) },
                    onRemove = { serverToRemove = item.server.sourceId },
                )
            }
            item(key = "add") {
                FilledTonalButton(onClick = onAddServer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                    Text(stringResource(R.string.settings_add_server), modifier = Modifier.padding(start = 8.dp))
                }
            }
            item(key = "refresh") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_refresh_all)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onRefreshAll),
                )
            }
            item(key = "scrobble") {
                SwitchRow(
                    title = stringResource(R.string.settings_scrobble),
                    summary = stringResource(R.string.settings_scrobble_summary),
                    icon = DesignR.drawable.ic_cloud,
                    checked = state.scrobble,
                    onCheckedChange = onScrobbleChange,
                )
            }
            item(key = "wifi-only") {
                SwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    summary = stringResource(R.string.settings_wifi_only_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = state.downloadWifiOnly,
                    onCheckedChange = onWifiOnlyChange,
                )
            }
            item(key = "auto-favorites") {
                SwitchRow(
                    title = stringResource(R.string.settings_auto_download_favorites),
                    summary = stringResource(R.string.settings_auto_download_favorites_summary),
                    icon = DesignR.drawable.ic_favorite,
                    checked = state.autoDownloadFavorites,
                    onCheckedChange = onAutoDownloadFavoritesChange,
                )
            }

            // --- Downloads ---
            item(key = "downloads") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.nav_downloads)) },
                    supportingContent = { Text(stringResource(R.string.settings_downloads_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenDownloads),
                )
            }

            // --- Wiedergabe ---
            item(key = "playback") { SectionHeader(stringResource(R.string.settings_playback)) }
            item(key = "replay-gain") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_replay_gain)) },
                    supportingContent = { Text(stringResource(state.replayGainMode.label)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_volume_up), contentDescription = null) },
                    modifier = Modifier.clickable { choosingReplayGain = true },
                )
            }
            item(key = "pre-amp") {
                PreAmpRow(
                    preAmpDb = state.preAmpDb,
                    enabled = state.replayGainMode != ReplayGainMode.OFF,
                    onPreAmpChange = onPreAmpChange,
                )
            }

            // Nur mit gekoppelter Uhr, auf der kleinaberlaut installiert ist (Wear-Konzept 5).
            if (watchAvailable) {
                item(key = "watch") { SectionHeader(stringResource(R.string.settings_watch)) }
                item(key = "watch-open") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_watch_open)) },
                        supportingContent = { Text(stringResource(R.string.settings_watch_open_summary)) },
                        leadingContent = { Icon(painterResource(DesignR.drawable.ic_watch), contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onOpenWatch),
                    )
                }
            }

            // --- Über ---
            item(key = "about") { SectionHeader(stringResource(R.string.settings_about)) }
            item(key = "about-app") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_about_app)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_info), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenAbout),
                )
            }
            item(key = "licenses") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                    supportingContent = { Text(stringResource(R.string.settings_licenses_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_description), contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenLicenses),
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
                onThemeModeChange(it)
                choosingTheme = false
            },
            onDismiss = { choosingTheme = false },
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
                onReplayGainModeChange(it)
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
                    onRemove(removing.sourceId)
                    serverToRemove = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { serverToRemove = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Ganze Zeile schaltet (größere Tippfläche, ein Element für Screenreader). */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String,
    icon: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = if (enabled) {
            ListItemDefaults.colors()
        } else {
            ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
                supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
            )
        },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

private const val DISABLED_ALPHA = 0.38f

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

private val ReplayGainMode.label: Int
    get() = when (this) {
        ReplayGainMode.OFF -> R.string.replay_gain_off
        ReplayGainMode.TRACK -> R.string.replay_gain_track
        ReplayGainMode.ALBUM -> R.string.replay_gain_album
        ReplayGainMode.AUTO -> R.string.replay_gain_auto
    }

/** Auswahl eines Werts mit Radio-Zeilen; ein Tipp wählt und schließt. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    summary: @Composable (T) -> String? = { null },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    val optionSummary = summary(option)
                    ListItem(
                        headlineContent = { Text(label(option)) },
                        supportingContent = optionSummary?.let { { Text(it) } },
                        leadingContent = { RadioButton(selected = option == selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = option == selected, role = Role.RadioButton) { onSelect(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Der Wert wird erst beim Loslassen gespeichert. */
@Composable
private fun PreAmpRow(preAmpDb: Float, enabled: Boolean, onPreAmpChange: (Float) -> Unit) {
    var value by remember(preAmpDb) { mutableFloatStateOf(preAmpDb) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_pre_amp)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_pre_amp_summary))
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onPreAmpChange(value) },
                    valueRange = ReplayGainSettings.MIN_PRE_AMP_DB..ReplayGainSettings.MAX_PRE_AMP_DB,
                    steps = (ReplayGainSettings.MAX_PRE_AMP_DB - ReplayGainSettings.MIN_PRE_AMP_DB).toInt() - 1,
                    enabled = enabled,
                )
            }
        },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_volume_up), contentDescription = null) },
        trailingContent = { Text(stringResource(R.string.settings_pre_amp_value, value)) },
    )
}

@Composable
private fun ServerRow(item: ServerItem, onEdit: () -> Unit, onSyncNow: () -> Unit, onRemove: () -> Unit) {
    val server = item.server
    ListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.server_edit), onClick = onEdit),
        headlineContent = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(server.username, server.baseUrl).joinToString(" @ "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val isError = item.passwordLost || item.error != null
                val status = when {
                    item.passwordLost -> stringResource(R.string.server_password_lost)
                    item.error != null -> stringResource(R.string.server_sync_failed, item.error)
                    server.lastFullSyncAt == 0L -> stringResource(R.string.server_never_synced)
                    else -> {
                        val ageMs = System.currentTimeMillis() - server.lastFullSyncAt
                        if (ageMs < MINUTE_MS) {
                            stringResource(R.string.server_synced_just_now)
                        } else {
                            stringResource(R.string.server_last_sync, relativeAge(ageMs))
                        }
                    }
                }
                Text(
                    status,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.autoDownload) {
                    Text(stringResource(R.string.server_auto_download), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_cloud), contentDescription = null) },
        trailingContent = {
            Row {
                IconButton(onClick = onSyncNow) {
                    Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.server_sync_now))
                }
                IconButton(onClick = onRemove) {
                    Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = stringResource(R.string.server_remove))
                }
            }
        },
    )
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** „vor 5 Minuten“ – eigene Texte statt `DateUtils`, das der System-Sprache folgt. */
@Composable
private fun relativeAge(ageMs: Long): String = when {
    ageMs < HOUR_MS -> (ageMs / MINUTE_MS).toInt().let { pluralStringResource(R.plurals.age_minutes, it, it) }
    ageMs < DAY_MS -> (ageMs / HOUR_MS).toInt().let { pluralStringResource(R.plurals.age_hours, it, it) }
    else -> (ageMs / DAY_MS).toInt().let { pluralStringResource(R.plurals.age_days, it, it) }
}

@Preview(showBackground = true)
@Composable
private fun SettingsContentPreview() {
    KleinaberlautTheme {
        SettingsContent(
            state = SettingsUiState(
                servers = listOf(
                    ServerItem(ServerInfo("1", "Navidrome", "https://musik.example.de/", "kal", 0), error = null, autoDownload = true),
                    ServerItem(ServerInfo("2", "Büro", "http://10.0.0.5:4533/", "kal", 0), error = "timeout"),
                ),
            ),
            watchAvailable = true,
            onThemeModeChange = {},
            onArtistImagesChange = {},
            onAlbumCoversChange = {},
            onUseLocalMediaChange = {},
            onAddServer = {},
            onEditServer = {},
            onOpenDownloads = {},
            onOpenWatch = {},
            onOpenAbout = {},
            onOpenLicenses = {},
            onSyncNow = {},
            onRemove = {},
            onRefreshAll = {},
            onWifiOnlyChange = {},
            onAutoDownloadFavoritesChange = {},
            onScrobbleChange = {},
            onReplayGainModeChange = {},
            onPreAmpChange = {},
        )
    }
}
