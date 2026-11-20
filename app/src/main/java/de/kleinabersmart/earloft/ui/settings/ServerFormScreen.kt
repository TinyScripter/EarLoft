package de.kleinabersmart.earloft.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.repository.ConnectionResult
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.permission.rememberLocalNetworkRequester

/** Create ([sourceId] `null`) or edit a server. */
@Composable
fun ServerFormScreen(
    sourceId: String?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServerFormViewModel = hiltViewModel<ServerFormViewModel, ServerFormViewModel.Factory> { it.create(sourceId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }
    ServerFormContent(
        state = state,
        onBack = onDone,
        onTypeChange = viewModel::onTypeChange,
        onNameChange = viewModel::onNameChange,
        onUrlChange = viewModel::onUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onAllowHttpChange = viewModel::onAllowHttpChange,
        onAutoDownloadChange = viewModel::onAutoDownloadChange,
        onSave = rememberLocalNetworkRequester(viewModel::save),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFormContent(
    state: ServerFormUiState,
    onBack: () -> Unit,
    onTypeChange: (SourceType) -> Unit,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAllowHttpChange: (Boolean) -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = {
                Text(stringResource(if (state.isEditing) R.string.edit_server_title else R.string.add_server_title))
            },
            navigationIcon = { BackButton(onBack) },
        )
        if (state.isLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        // On low windows, result and buttons stay pinned at the bottom, otherwise they'd be out of sight below the fields.
        val pinned = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 700.dp
        val actions: @Composable ColumnScope.() -> Unit = {
            state.result?.let { ResultText(it, state.type) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave, enabled = state.canSubmit) {
                    Text(stringResource(R.string.add_server_save))
                }
                if (state.isBusy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        Column(
            modifier = Modifier
                .then(if (pinned) Modifier.weight(1f) else Modifier.imePadding().navigationBarsPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The type is fixed after creation: songs, favorites and playlists depend on it.
            if (!state.isEditing) ServerTypeSelector(state.type, onTypeChange)
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.add_server_url)) },
                placeholder = { Text(stringResource(R.string.add_server_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.add_server_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.add_server_password)) },
                supportingText = when {
                    !state.isEditing -> null
                    state.hasStoredPassword -> {
                        { Text(stringResource(if (state.type.signsIn) R.string.edit_server_login_keep else R.string.edit_server_password_keep)) }
                    }
                    else -> {
                        { Text(stringResource(if (state.type.signsIn) R.string.edit_server_login_required else R.string.edit_server_password_required)) }
                    }
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.add_server_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            // Below the text fields instead of below the address: the hint already appears while typing "http://" and would otherwise
            // push the following fields away.
            if (state.isHttp) {
                if (state.onlyHttp) {
                    Text(stringResource(R.string.add_server_http_only), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    stringResource(R.string.add_server_http_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                // The whole row toggles; the checkbox itself has no click and no name of its own.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = state.allowHttp, role = Role.Checkbox, onValueChange = onAllowHttpChange),
                ) {
                    Checkbox(checked = state.allowHttp, onCheckedChange = null)
                    Text(stringResource(R.string.add_server_allow_http), modifier = Modifier.padding(start = 8.dp))
                }
            }
            // Like the checkbox above: the whole row toggles.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = state.autoDownload, role = Role.Switch, onValueChange = onAutoDownloadChange),
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.server_form_auto_download), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.server_form_auto_download_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.autoDownload, onCheckedChange = null)
            }

            if (!pinned) {
                actions()
                Spacer(Modifier.size(16.dp))
            }
        }
        if (pinned) {
            Column(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
    }
}

/** Navidrome/Subsonic, Jellyfin or Emby; the row is a selection group for accessibility services. */
@Composable
private fun ServerTypeSelector(selected: SourceType, onSelect: (SourceType) -> Unit) {
    val label = stringResource(R.string.add_server_type)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        SERVER_TYPES.forEachIndexed { index, (type, text) ->
            SegmentedButton(
                selected = type == selected,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, SERVER_TYPES.size),
            ) {
                Text(stringResource(text), maxLines = 1)
            }
        }
    }
}

private val SERVER_TYPES = listOf(
    SourceType.SUBSONIC to R.string.add_server_type_subsonic,
    SourceType.JELLYFIN to R.string.add_server_type_jellyfin,
    SourceType.EMBY to R.string.add_server_type_emby,
)

/** Jellyfin and Emby: the password is only used to sign in, the token is stored. */
private val SourceType.signsIn: Boolean get() = this == SourceType.JELLYFIN || this == SourceType.EMBY

@Composable
private fun ResultText(result: ConnectionResult, type: SourceType) {
    val (text, isError) = when (result) {
        is ConnectionResult.Success ->
            stringResource(R.string.add_server_success, result.serverName, result.serverVersion.orEmpty()) to false
        ConnectionResult.InvalidUrl -> stringResource(R.string.add_server_invalid_url) to true
        ConnectionResult.WrongCredentials -> stringResource(R.string.add_server_wrong_credentials) to true
        ConnectionResult.UnexpectedServer -> stringResource(notServerText(type)) to true
        is ConnectionResult.Unreachable -> stringResource(R.string.add_server_unreachable, result.reason.orEmpty()) to true
        is ConnectionResult.ServerError ->
            stringResource(R.string.add_server_server_error, result.code, result.message.orEmpty()) to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Preview(showBackground = true)
@Composable
private fun ServerFormContentPreview() {
    EarLoftTheme {
        ServerFormContent(
            state = ServerFormUiState(
                url = "http://10.0.2.2:4533",
                username = "kal",
                password = "x",
                result = ConnectionResult.Success("navidrome", "0.64.2"),
            ),
            onBack = {},
            onTypeChange = {},
            onNameChange = {},
            onUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onAllowHttpChange = {},
            onAutoDownloadChange = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerFormEditPreview() {
    EarLoftTheme {
        ServerFormContent(
            state = ServerFormUiState(
                isEditing = true,
                name = "Navidrome",
                url = "https://musik.example.de/",
                username = "kal",
                hasStoredPassword = true,
                autoDownload = true,
            ),
            onBack = {},
            onTypeChange = {},
            onNameChange = {},
            onUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onAllowHttpChange = {},
            onAutoDownloadChange = {},
            onSave = {},
        )
    }
}
