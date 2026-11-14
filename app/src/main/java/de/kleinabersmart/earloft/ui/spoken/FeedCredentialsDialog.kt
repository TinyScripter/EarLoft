package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl

/**
 * Benutzername und Passwort eines geschützten Feeds (Konzept 12.10): beim Hinzufügen nach einer 401 und aus den
 * Abo-Einstellungen. [feedUrl] nennt den Host, an den sie gehen; bei `http://` warnt der Dialog. [onRemove] nur, wenn schon
 * welche gespeichert sind.
 */
@Composable
fun FeedCredentialsDialog(
    feedUrl: String,
    rejected: Boolean,
    onConfirm: (PodcastRepository.Credentials) -> Unit,
    onDismiss: () -> Unit,
    initialUsername: String = "",
    onRemove: (() -> Unit)? = null,
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    val ready = username.isNotBlank() && password.isNotEmpty()
    val confirm = { if (ready) onConfirm(PodcastRepository.Credentials(username.trim(), password)) }
    val host = normalizeFeedUrl(feedUrl)?.host ?: feedUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_credentials_title)) },
        text = {
            Column {
                Text(stringResource(R.string.podcast_credentials_message, host))
                if (isUnencrypted(feedUrl)) {
                    Text(
                        stringResource(R.string.podcast_credentials_http),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.add_server_username)) },
                    singleLine = true,
                    isError = rejected,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.add_server_password)) },
                    singleLine = true,
                    isError = rejected,
                    supportingText = if (rejected) {
                        { Text(stringResource(R.string.podcast_credentials_rejected)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = ready) { Text(stringResource(R.string.podcast_credentials_confirm)) }
        },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
