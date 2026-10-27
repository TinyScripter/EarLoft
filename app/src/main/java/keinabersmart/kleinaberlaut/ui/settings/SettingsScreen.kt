package keinabersmart.kleinaberlaut.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.ServerInfo
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
    modifier: Modifier = Modifier,
) {
    var serverToRemove by rememberSaveable { mutableStateOf<String?>(null) }

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
            item(key = "downloads") { SectionHeader(stringResource(R.string.settings_downloads)) }
            item(key = "wifi-only") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_wifi_only)) },
                    supportingContent = { Text(stringResource(R.string.settings_wifi_only_summary)) },
                    leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    trailingContent = { Switch(checked = state.downloadWifiOnly, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = state.downloadWifiOnly,
                        role = Role.Switch,
                        onValueChange = onWifiOnlyChange,
                    ),
                )
            }
        }
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
                val status = when {
                    item.error != null -> stringResource(R.string.server_sync_failed, item.error)
                    server.lastFullSyncAt == 0L -> stringResource(R.string.server_never_synced)
                    System.currentTimeMillis() - server.lastFullSyncAt < DateUtils.MINUTE_IN_MILLIS ->
                        stringResource(R.string.server_synced_just_now)
                    else -> stringResource(
                        R.string.server_last_sync,
                        DateUtils.getRelativeTimeSpanString(server.lastFullSyncAt).toString(),
                    )
                }
                Text(
                    status,
                    color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
        )
    }
}
