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
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.core.model.ServerInfo
import keinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onAddServer = onAddServer,
        onSyncNow = viewModel::syncNow,
        onRemove = viewModel::remove,
        onRefreshAll = viewModel::refreshAll,
        onWifiOnlyChange = viewModel::setDownloadWifiOnly,
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
    onBack: () -> Unit,
    onAddServer: () -> Unit,
    onSyncNow: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onScrobbleChange: (Boolean) -> Unit,
    onReplayGainModeChange: (ReplayGainMode) -> Unit,
    onPreAmpChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingReplayGain by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { BackButton(onBack) },
        )
        if (state.isSyncing) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.navigationBarsPadding()) {
            item(key = "servers") { SectionHeader(stringResource(R.string.settings_servers)) }
            if (state.servers.isEmpty()) {
                item(key = "no-servers") { EmptyHint(stringResource(R.string.settings_no_servers)) }
            }
            items(state.servers, key = { it.server.sourceId }) { item ->
                ServerRow(
                    item = item,
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
            item(key = "library") { SectionHeader(stringResource(R.string.settings_library)) }
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
            item(key = "downloads") { SectionHeader(stringResource(R.string.settings_downloads)) }
            item(key = "wifi-only") {
                SwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    summary = stringResource(R.string.settings_wifi_only_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = state.downloadWifiOnly,
                    onCheckedChange = onWifiOnlyChange,
                )
            }
        }
    }

    if (choosingReplayGain) {
        ReplayGainModeDialog(
            selected = state.replayGainMode,
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
private fun SwitchRow(title: String, summary: String, icon: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

private val ReplayGainMode.label: Int
    get() = when (this) {
        ReplayGainMode.OFF -> R.string.replay_gain_off
        ReplayGainMode.TRACK -> R.string.replay_gain_track
        ReplayGainMode.ALBUM -> R.string.replay_gain_album
        ReplayGainMode.AUTO -> R.string.replay_gain_auto
    }

@Composable
private fun ReplayGainModeDialog(selected: ReplayGainMode, onSelect: (ReplayGainMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_replay_gain)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ReplayGainMode.entries.forEach { mode ->
                    ListItem(
                        headlineContent = { Text(stringResource(mode.label)) },
                        supportingContent = if (mode == ReplayGainMode.AUTO) {
                            { Text(stringResource(R.string.replay_gain_auto_summary)) }
                        } else {
                            null
                        },
                        leadingContent = { RadioButton(selected = mode == selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.selectable(selected = mode == selected, role = Role.RadioButton) { onSelect(mode) },
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
private fun ServerRow(item: ServerItem, onSyncNow: () -> Unit, onRemove: () -> Unit) {
    val server = item.server
    ListItem(
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
                    ServerItem(ServerInfo("1", "Navidrome", "https://musik.example.de/", "kal", 0), error = null),
                    ServerItem(ServerInfo("2", "Büro", "http://10.0.0.5:4533/", "kal", 0), error = "timeout"),
                ),
            ),
            onBack = {},
            onAddServer = {},
            onSyncNow = {},
            onRemove = {},
            onRefreshAll = {},
            onWifiOnlyChange = {},
            onScrobbleChange = {},
            onReplayGainModeChange = {},
            onPreAmpChange = {},
        )
    }
}
