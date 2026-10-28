package keinabersmart.kleinaberlaut.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.repository.ConnectionResult
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.ui.permission.rememberLocalNetworkRequester

/** Server anlegen ([sourceId] `null`) oder bearbeiten. */
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
        onNameChange = viewModel::onNameChange,
        onUrlChange = viewModel::onUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onAllowHttpChange = viewModel::onAllowHttpChange,
        onAutoDownloadChange = viewModel::onAutoDownloadChange,
        onTest = rememberLocalNetworkRequester(viewModel::test),
        onSave = rememberLocalNetworkRequester(viewModel::save),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFormContent(
    state: ServerFormUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAllowHttpChange: (Boolean) -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
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
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.add_server_url)) },
                placeholder = { Text(stringResource(R.string.add_server_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isHttp) {
                Text(
                    stringResource(R.string.add_server_http_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                // Die ganze Zeile schaltet um; die Checkbox selbst trägt keinen Klick und keinen eigenen Namen.
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
                        { Text(stringResource(R.string.edit_server_password_keep)) }
                    }
                    else -> {
                        { Text(stringResource(R.string.edit_server_password_required)) }
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
            // Wie die Checkbox oben: Die ganze Zeile schaltet.
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

            state.result?.let { ResultText(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest, enabled = state.canSubmit) {
                    Text(stringResource(R.string.add_server_test))
                }
                Button(onClick = onSave, enabled = state.canSubmit) {
                    Text(stringResource(R.string.add_server_save))
                }
                if (state.isBusy) CircularProgressIndicator(Modifier.size(24.dp))
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ResultText(result: ConnectionResult) {
    val (text, isError) = when (result) {
        is ConnectionResult.Success ->
            stringResource(R.string.add_server_success, result.serverName, result.serverVersion.orEmpty()) to false
        ConnectionResult.InvalidUrl -> stringResource(R.string.add_server_invalid_url) to true
        ConnectionResult.WrongCredentials -> stringResource(R.string.add_server_wrong_credentials) to true
        ConnectionResult.NotSubsonic -> stringResource(R.string.add_server_not_subsonic) to true
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
    KleinaberlautTheme {
        ServerFormContent(
            state = ServerFormUiState(
                url = "http://10.0.2.2:4533",
                username = "kal",
                password = "x",
                result = ConnectionResult.Success("navidrome", "0.64.2"),
            ),
            onBack = {},
            onNameChange = {},
            onUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onAllowHttpChange = {},
            onAutoDownloadChange = {},
            onTest = {},
            onSave = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ServerFormEditPreview() {
    KleinaberlautTheme {
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
            onNameChange = {},
            onUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onAllowHttpChange = {},
            onAutoDownloadChange = {},
            onTest = {},
            onSave = {},
        )
    }
}
