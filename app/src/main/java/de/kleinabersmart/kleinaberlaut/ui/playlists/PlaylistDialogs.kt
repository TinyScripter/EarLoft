package de.kleinabersmart.kleinaberlaut.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.kleinaberlaut.R
import de.kleinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import de.kleinabersmart.kleinaberlaut.core.model.ServerInfo
import de.kleinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import de.kleinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Wählt eine Playlist (oder legt eine neue an) und fügt [songIds] hinzu. */
@Composable
fun AddToPlaylistDialog(
    songIds: List<Long>,
    onDismiss: () -> Unit,
    viewModel: AddToPlaylistViewModel = hiltViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val confirm = { name: String ->
        snackbars.show(resources.getString(R.string.playlist_added, name))
        onDismiss()
    }

    val currentServers = servers
    if (creating && currentServers != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            servers = currentServers,
            onConfirm = { name, serverSourceId ->
                viewModel.create(name, serverSourceId, songIds)
                confirm(name)
            },
            onDismiss = onDismiss,
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_to_playlist)) },
        text = {
            LazyColumn {
                item(key = "new") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.playlist_new)) },
                        leadingContent = { Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { creating = true },
                    )
                }
                items(playlists.orEmpty(), key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(playlistLocation(playlist.serverName)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            viewModel.add(playlist.id, songIds)
                            confirm(playlist.name)
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Name eingeben; mit [servers] zusätzlich, wo die Playlist liegt (vorausgewählt: der erste Server,
 * damit sie auch in anderen Apps erscheint). Ohne [servers] (Umbenennen) gibt es keine Auswahl.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (name: String, serverSourceId: String?) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
    servers: List<ServerInfo>? = null,
) {
    // Vorhandener Name ist markiert: Tippen ersetzt ihn, Pfeiltasten ergänzen.
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length)))
    }
    var serverSourceId by rememberSaveable { mutableStateOf(servers?.firstOrNull()?.sourceId) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (!servers.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.selectableGroup()) {
                        LocationOption(stringResource(R.string.playlist_location_device), serverSourceId == null) { serverSourceId = null }
                        servers.forEach { server ->
                            LocationOption(
                                label = stringResource(R.string.playlist_location_server, server.name),
                                selected = serverSourceId == server.sourceId,
                            ) { serverSourceId = server.sourceId }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.text.trim(), serverSourceId) }, enabled = name.text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun LocationOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

/** „Nur auf diesem Gerät“ bzw. der Servername. */
@Composable
fun playlistLocation(serverName: String?): String =
    serverName ?: stringResource(R.string.playlist_location_device)

/** Symbol für Playlists in Listen. */
@Composable
fun PlaylistIcon(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(DesignR.drawable.ic_queue_music),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun PlaylistNameDialogPreview() {
    KleinaberlautTheme {
        PlaylistNameDialog(
            title = "Neue Playlist",
            confirmLabel = "Anlegen",
            onConfirm = { _, _ -> },
            onDismiss = {},
            initialName = "Sommer",
            servers = listOf(ServerInfo("nd", "Navidrome", "https://music.example", "kal", 0)),
        )
    }
}
