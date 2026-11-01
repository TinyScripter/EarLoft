package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import keinabersmart.kleinaberlaut.ui.library.BackButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.AddServerResult
import keinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
import keinabersmart.kleinaberlaut.core.model.SpokenServer
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data class Failed(val message: Int) : ConnectState
    data object Connected : ConnectState
}

@HiltViewModel
class AudiobookshelfViewModel @Inject constructor(
    private val servers: AudiobookshelfServers,
    private val refresher: SpokenRefresher,
) : ViewModel() {

    val list: StateFlow<List<SpokenServer>> = servers.servers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    /** [serverId] `null` = neuer Server, sonst werden die Angaben dieses Servers geändert. */
    fun connect(serverId: String?, name: String, url: String, apiKey: String) {
        if (_connect.value == ConnectState.Connecting) return
        _connect.value = ConnectState.Connecting
        viewModelScope.launch {
            val result = if (serverId == null) servers.add(name, url, apiKey) else servers.update(serverId, name, url, apiKey)
            _connect.value = when (result) {
                is AddServerResult.Added -> {
                    refresher.refreshAll()
                    ConnectState.Connected
                }
                AddServerResult.InvalidUrl -> ConnectState.Failed(R.string.spoken_abs_error_url)
                AddServerResult.Unreachable -> ConnectState.Failed(R.string.spoken_abs_error_unreachable)
                AddServerResult.AuthFailed -> ConnectState.Failed(R.string.spoken_abs_error_auth)
                AddServerResult.NoBookLibrary -> ConnectState.Failed(R.string.spoken_abs_error_library)
            }
        }
    }

    fun syncNow() = refresher.refreshAll()

    suspend fun editable(serverId: String) = servers.editable(serverId)

    fun setEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            servers.setEnabled(serverId, enabled)
            if (enabled) refresher.refreshAll()
        }
    }

    fun remove(serverId: String) {
        viewModelScope.launch { servers.remove(serverId) }
    }
}

/** Die Audiobookshelf-Server in den Hörbuch-Einstellungen: auflisten, ein-/ausschalten, bearbeiten, abgleichen, entfernen, hinzufügen. */
@Composable
fun AudiobookshelfSection(
    onAdd: () -> Unit,
    onEdit: (serverId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudiobookshelfViewModel = hiltViewModel(),
) {
    val servers by viewModel.list.collectAsStateWithLifecycle()
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier) {
        if (servers.isEmpty()) EmptyHint(stringResource(R.string.spoken_abs_none))
        servers.forEach { server ->
            ListItem(
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.server_edit)) { onEdit(server.serverId) },
                headlineContent = { Text(server.name) },
                supportingContent = {
                    Column {
                        Text(server.baseUrl)
                        if (!server.enabled) {
                            Text(stringResource(R.string.server_disabled), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var key by rememberSaveable { mutableStateOf("") }
    // Beim Bearbeiten: leer = gespeicherter Schlüssel bleibt.
    var hasStoredKey by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(serverId) {
        if (serverId == null) return@LaunchedEffect
        val server = viewModel.editable(serverId)
        if (server == null) {
            onDone()
        } else if (name.isEmpty() && url.isEmpty()) {
            name = server.name
            url = server.baseUrl
            hasStoredKey = server.hasKey
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
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.spoken_abs_url)) },
                placeholder = { Text(stringResource(R.string.spoken_abs_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            (connectState as? ConnectState.Failed)?.let {
                Text(
                    stringResource(it.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = { viewModel.connect(serverId, name, url, key) },
                enabled = url.isNotBlank() && (key.isNotBlank() || hasStoredKey) && connectState != ConnectState.Connecting,
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
