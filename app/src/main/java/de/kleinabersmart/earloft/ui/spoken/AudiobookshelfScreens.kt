package de.kleinabersmart.earloft.ui.spoken

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import de.kleinabersmart.earloft.ui.library.BackButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.permission.rememberLocalNetworkRequester
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

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
                                stringResource(if (server.openId) R.string.spoken_abs_login_lost else R.string.spoken_abs_key_lost),
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
            // Mehrere Buch-Bibliotheken: jede abwählbar (books_improvements M3); die letzte gezeigte bleibt.
            if (server.libraries.size > 1) {
                Text(
                    stringResource(R.string.spoken_abs_libraries),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, top = 4.dp),
                )
                val shownCount = server.libraries.count { it.shown }
                server.libraries.forEach { library ->
                    val locked = library.shown && shownCount == 1
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = library.shown,
                                enabled = server.enabled && !locked,
                                role = Role.Checkbox,
                                onValueChange = { viewModel.setLibraryShown(server.serverId, library.libraryId, it) },
                            )
                            .padding(start = 44.dp, end = 16.dp),
                    ) {
                        Checkbox(checked = library.shown, onCheckedChange = null, enabled = server.enabled && !locked)
                        Text(library.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
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

/** Die eigene Rückleitung für Browser ohne Auth Tab; muss in Audiobookshelf eingetragen sein (Konzept 12.11). */
private const val OWN_REDIRECT_URI = "earloft://oauth"

/**
 * Der Browser, der die Anmeldung per OpenID öffnet, wenn er Auth Tab kann (Chrome ab 137): Er gibt die Rückleitung auf
 * `audiobookshelf://oauth` direkt an EarLoft, ohne Intent-Filter, der der offiziellen App in die Quere käme. `null`: Der
 * Standardbrowser kann das nicht, es bleibt [OWN_REDIRECT_URI].
 */
private fun authTabBrowser(context: Context): String? =
    CustomTabsClient.getPackageName(context, null)?.takeIf { CustomTabsClient.isAuthTabSupported(context, it) }

/**
 * Formular zum Verbinden mit einem Audiobookshelf-Server per API-Schlüssel oder OpenID ([serverId] `null`) oder zum Bearbeiten
 * eines vorhandenen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookshelfFormScreen(
    serverId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudiobookshelfViewModel = hiltViewModel(),
) {
    val connectState by viewModel.connect.collectAsStateWithLifecycle()
    val loginOptions by viewModel.loginOptions.collectAsStateWithLifecycle()
    val browserUrl by viewModel.browser.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val authTab = remember { authTabBrowser(context) }
    val authTabLauncher = rememberLauncherForActivityResult(AuthTabIntent.AuthenticateUserResultContract()) { result ->
        viewModel.onBrowserResult(result.resultUri?.takeIf { result.resultCode == AuthTabIntent.RESULT_OK }?.toString())
    }
    LaunchedEffect(browserUrl) {
        val address = browserUrl?.toUri() ?: return@LaunchedEffect
        viewModel.browserOpened()
        if (authTab != null) {
            AuthTabIntent.Builder().build()
                .apply { intent.setPackage(authTab) }
                .launch(authTabLauncher, address, AbsAuthApi.DEFAULT_REDIRECT_URI.substringBefore(':'))
        } else {
            // Die Rückleitung kommt über OpenIdRedirectActivity.
            CustomTabsIntent.Builder().build().launchUrl(context, address)
        }
    }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var key by viewModel::keyInput
    // Beim Bearbeiten: leer = gespeicherter Schlüssel bzw. die gespeicherte Anmeldung per OpenID bleibt.
    var hasStoredKey by rememberSaveable { mutableStateOf(false) }
    var usesOpenId by rememberSaveable { mutableStateOf(false) }
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
            usesOpenId = server.openId
            // Ein schon per HTTP eingerichteter Server war bestätigt.
            allowHttp = server.baseUrl.startsWith("http://", ignoreCase = true)
        }
    }
    LaunchedEffect(connectState) { if (connectState == ConnectState.Connected) onDone() }
    LaunchedEffect(url) { viewModel.onUrlChanged(url) }

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
                    Text(
                        stringResource(
                            when {
                                hasStoredKey && usesOpenId -> R.string.spoken_abs_openid_keep
                                hasStoredKey -> R.string.spoken_abs_key_keep
                                else -> R.string.spoken_abs_key_summary
                            },
                        ),
                    )
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
                    if (it.arg != null) stringResource(it.message, it.arg) else stringResource(it.message),
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
            // Nur, wenn der Server unter dieser Adresse OpenID anbietet (`/status`).
            loginOptions?.takeIf { it.openId }?.let { options ->
                OutlinedButton(
                    onClick = rememberLocalNetworkRequester {
                        viewModel.startOpenId(serverId, name, url, if (authTab != null) AbsAuthApi.DEFAULT_REDIRECT_URI else OWN_REDIRECT_URI)
                    },
                    enabled = (!isHttp || allowHttp) && connectState != ConnectState.Connecting,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.spoken_abs_openid))
                }
                if (authTab == null) {
                    Text(
                        stringResource(R.string.spoken_abs_openid_no_auth_tab, OWN_REDIRECT_URI),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
