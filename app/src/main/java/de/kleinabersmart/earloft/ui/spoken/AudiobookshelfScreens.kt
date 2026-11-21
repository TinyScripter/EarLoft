package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.text.KeyboardActions
import de.kleinabersmart.earloft.ui.components.RemoveServerButton
import de.kleinabersmart.earloft.ui.components.passwordTransformation
import de.kleinabersmart.earloft.ui.components.PasswordVisibilityToggle
import de.kleinabersmart.earloft.ui.components.rememberDiscardGuard
import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
import de.kleinabersmart.earloft.ui.components.readableWidth
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

/** The app's own redirect for browsers without Auth Tab; must be registered in Audiobookshelf (concept 12.11). */
private const val OWN_REDIRECT_URI = "earloft://oauth"

/**
 * The browser that opens the OpenID login, if it supports Auth Tab (Chrome 137+): it passes the redirect to
 * `audiobookshelf://oauth` directly to EarLoft, without an intent filter that would interfere with the official app. `null`: the
 * default browser can't do that, [OWN_REDIRECT_URI] remains.
 */
private fun authTabBrowser(context: Context): String? =
    CustomTabsClient.getPackageName(context, null)?.takeIf { CustomTabsClient.isAuthTabSupported(context, it) }

/**
 * Form for connecting to an Audiobookshelf server via API key or OpenID ([serverId] `null`) or for editing
 * an existing one.
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
            // The redirect arrives via OpenIdRedirectActivity.
            CustomTabsIntent.Builder().build().launchUrl(context, address)
        }
    }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var key by viewModel::keyInput
    // When editing: empty = the stored key or the stored OpenID login is kept.
    var hasStoredKey by rememberSaveable { mutableStateOf(false) }
    var usesOpenId by rememberSaveable { mutableStateOf(false) }
    // HTTP only after explicit confirmation (concept 10), as in the music server form.
    var allowHttp by rememberSaveable { mutableStateOf(false) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    // Editing: the fields are empty until the server is read; a bar shows that, as in the music server form.
    var loaded by rememberSaveable { mutableStateOf(serverId == null) }
    // The values when the form was ready; anything typed since is asked about before leaving.
    var initial by rememberSaveable { mutableStateOf<List<String>?>(if (serverId == null) listOf("", "", "", "false") else null) }
    val fields = listOf(name, url, key, allowHttp.toString())
    val close = rememberDiscardGuard(
        dirty = initial != null && fields != initial && connectState != ConnectState.Connected,
        onClose = onDone,
    )
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
            // A server already set up via HTTP was confirmed.
            allowHttp = server.baseUrl.startsWith("http://", ignoreCase = true)
            initial = listOf(name, url, key, allowHttp.toString())
        }
        loaded = true
    }
    // Typing clears the last error, as in the music server form.
    LaunchedEffect(fields) { viewModel.clearFailure() }
    LaunchedEffect(connectState) { if (connectState == ConnectState.Connected) onDone() }
    LaunchedEffect(url) { viewModel.onUrlChanged(url) }

    // As narrow as the music server form on wide windows.
    Column(modifier.fillMaxSize().readableWidth().imePadding()) {
        TopAppBar(
            title = { Text(stringResource(if (serverId == null) R.string.spoken_abs_add else R.string.spoken_abs_edit)) },
            navigationIcon = { BackButton(close) },
        )
        if (!loaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        val canConnect = url.isNotBlank() && (key.isNotBlank() || hasStoredKey) && (!isHttp || allowHttp) &&
            connectState != ConnectState.Connecting
        // From Android 17 the app reaches servers on the home network only with this permission (as in the music form).
        val connect = rememberLocalNetworkRequester { viewModel.connect(serverId, name, url, key) }
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.spoken_abs_url)) },
                placeholder = { Text(stringResource(R.string.spoken_abs_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
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
                visualTransformation = passwordTransformation(keyVisible),
                trailingIcon = { PasswordVisibilityToggle(keyVisible) { keyVisible = !keyVisible } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // As in the music form, last and optional: if it stays empty, the server is named after the host of its address.
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.add_server_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Enter on the last field connects, like the button.
                keyboardActions = KeyboardActions(onDone = { if (canConnect) connect() }),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // Below the key instead of below the address: the hint appears as soon as "http://" is typed and would otherwise push
            // the following fields away.
            if (isHttp) {
                Text(
                    stringResource(R.string.spoken_abs_http_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // The whole row toggles; the checkbox itself carries no click and no name of its own.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // At least 48 dp tall, like every other row to tap.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
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
                onClick = connect,
                enabled = canConnect,
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
            // Only if the server offers OpenID at this address (`/status`).
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
            // Removing sits here, at the end of the form, as for music servers (no longer in the list next to the switch).
            if (serverId != null) {
                RemoveServerButton(
                    title = stringResource(R.string.spoken_abs_remove),
                    text = stringResource(R.string.spoken_abs_remove_message, name),
                    onRemove = { viewModel.remove(serverId, onRemoved = onDone) },
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}
