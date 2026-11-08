package de.kleinabersmart.tinyamp.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import de.kleinabersmart.tinyamp.ui.library.BackButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.components.EmptyHint
import de.kleinabersmart.tinyamp.ui.components.LoadFailedHint
import de.kleinabersmart.tinyamp.ui.permission.rememberLocalNetworkRequester
import de.kleinabersmart.tinyamp.ui.valueOrNull
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

/** Die Audiobookshelf-Server in den Bücher-Einstellungen: auflisten, ein-/ausschalten, bearbeiten, abgleichen, entfernen, hinzufügen. */
@Composable
fun AudiobookshelfSection(
    onAdd: () -> Unit,
    onEdit: (serverId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudiobookshelfViewModel = hiltViewModel(),
) {
    val state by viewModel.list.collectAsStateWithLifecycle()
    val withoutKey by viewModel.withoutKey.collectAsStateWithLifecycle()
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val servers = state.valueOrNull.orEmpty()

    Column(modifier) {
        if (state == LoadState.Failed) LoadFailedHint()
        if (state is LoadState.Ready && servers.isEmpty()) EmptyHint(stringResource(R.string.spoken_abs_none))
        servers.forEach { server ->
            ListItem(
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.server_edit)) { onEdit(server.serverId) },
                headlineContent = { Text(server.name) },
                supportingContent = {
                    Column {
                        Text(server.baseUrl)
                        if (!server.enabled) {
                            Text(stringResource(R.string.server_disabled), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (server.serverId in withoutKey) {
                            Text(
                                stringResource(R.string.spoken_abs_key_lost),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_book), contentDescription = null) },
                trailingContent = {
                    Row {
                        if (server.enabled) {
                            IconButton(onClick = viewModel::syncNow) {
                                Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.spoken_abs_sync))
                            }
                        }
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { viewModel.setEnabled(server.serverId, it) },
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                },
            )
            TextButton(onClick = { removing = server.serverId }, modifier = Modifier.padding(start = 56.dp)) {
                Text(stringResource(R.string.spoken_abs_remove))
            }
        }
        FilledTonalButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
            Text(stringResource(R.string.spoken_abs_add), modifier = Modifier.padding(start = 8.dp))
        }
    }

    val toRemove = servers.firstOrNull { it.serverId == removing }
    if (toRemove != null) {
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.spoken_abs_remove)) },
            text = { Text(stringResource(R.string.spoken_abs_remove_message, toRemove.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(toRemove.serverId)
                    removing = null
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Formular zum Verbinden mit einem Audiobookshelf-Server per API-Schlüssel ([serverId] `null`) oder zum Bearbeiten eines vorhandenen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookshelfFormScreen(
    serverId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudiobookshelfViewModel = hiltViewModel(),
) {
    val connectState by viewModel.connect.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var key by viewModel::keyInput
    // Beim Bearbeiten: leer = gespeicherter Schlüssel bleibt.
    var hasStoredKey by rememberSaveable { mutableStateOf(false) }
    // HTTP nur nach ausdrücklicher Bestätigung (Konzept 10), wie im Formular der Musik-Server.
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    val isHttp = url.trim().startsWith("http://", ignoreCase = true)
    LaunchedEffect(serverId) {
        if (serverId == null) return@LaunchedEffect
        val server = viewModel.editable(serverId)
        if (server == null) {
            onDone()
        } else if (name.isEmpty() && url.isEmpty()) {
            name = server.name
            url = server.baseUrl
            hasStoredKey = server.hasKey
            // Ein schon per HTTP eingerichteter Server war bestätigt.
            allowHttp = server.baseUrl.startsWith("http://", ignoreCase = true)
        }
    }
    LaunchedEffect(connectState) { if (connectState == ConnectState.Connected) onDone() }

    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(if (serverId == null) R.string.spoken_abs_add else R.string.spoken_abs_edit)) },
            navigationIcon = { BackButton(onDone) },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.spoken_abs_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.spoken_abs_url)) },
                placeholder = { Text(stringResource(R.string.spoken_abs_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.spoken_abs_key)) },
                supportingText = {
                    Text(stringResource(if (hasStoredKey) R.string.spoken_abs_key_keep else R.string.spoken_abs_key_summary))
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // Unter dem Schlüssel statt unter der Adresse: Der Hinweis erscheint schon beim Tippen von „http://“ und schöbe sonst die
            // folgenden Felder weg.
            if (isHttp) {
                Text(
                    stringResource(R.string.spoken_abs_http_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Die ganze Zeile schaltet um; die Checkbox selbst trägt keinen Klick und keinen eigenen Namen.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = allowHttp, role = Role.Checkbox, onValueChange = { allowHttp = it }),
                ) {
                    Checkbox(checked = allowHttp, onCheckedChange = null)
                    Text(stringResource(R.string.add_server_allow_http), modifier = Modifier.padding(start = 8.dp))
                }
            }
            (connectState as? ConnectState.Failed)?.let {
                Text(
                    stringResource(it.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                // Ab Android 17 erreicht die App Server im Heimnetz nur mit dieser Berechtigung (wie im Musik-Formular).
                onClick = rememberLocalNetworkRequester { viewModel.connect(serverId, name, url, key) },
                enabled = url.isNotBlank() && (key.isNotBlank() || hasStoredKey) && (!isHttp || allowHttp) &&
                    connectState != ConnectState.Connecting,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            connectState == ConnectState.Connecting -> R.string.spoken_abs_connecting
                            serverId == null -> R.string.spoken_abs_connect
                            else -> R.string.spoken_abs_save
                        },
                    ),
                )
            }
        }
    }
}
