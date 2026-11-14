package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.UploadTarget
import de.kleinabersmart.earloft.ui.formatBytes

/**
 * Dialoge zum Hochladen eines Buchs aus ARD Sounds zu Audiobookshelf (`books_improvements.md` M9): Ziel wählen bzw. bestätigen,
 * Laden und Hochladen mit Fortschritt (abbrechbar), Hinweise am Ende.
 */
@Composable
fun ArdUploadDialogs(state: UploadState, title: String, onConfirm: (UploadTarget) -> Unit, onDismiss: () -> Unit) {
    when (state) {
        UploadState.Idle -> Unit
        UploadState.Loading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ard_upload_title)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        UploadState.NoTarget -> Notice(R.string.ard_upload_no_target, onDismiss)
        is UploadState.Choosing -> Choosing(state, title, onConfirm, onDismiss)
        is UploadState.Downloading -> Progress(stringResource(R.string.ard_upload_downloading), null, onDismiss)
        is UploadState.Uploading -> Progress(
            stringResource(R.string.ard_upload_uploading, formatBytes(state.sent), formatBytes(state.total)),
            if (state.total > 0) state.sent.toFloat() / state.total else null,
            onDismiss,
        )
        is UploadState.Finished -> Notice(
            when (state.reason) {
                UploadFinish.OFFLINE -> R.string.podcast_download_offline
                UploadFinish.WAITING_FOR_WIFI -> R.string.ard_upload_waiting_wifi
                UploadFinish.DOWNLOAD_FAILED -> R.string.ard_upload_download_failed
                UploadFinish.EXISTS -> R.string.ard_upload_exists
                UploadFinish.NO_PERMISSION -> R.string.ard_upload_no_permission
                UploadFinish.SERVER_ERROR -> R.string.ard_upload_server_error
                UploadFinish.FAILED -> R.string.ard_upload_failed
                UploadFinish.NOT_YET_ON_SERVER -> R.string.ard_upload_not_yet
                UploadFinish.KEPT_PLAYING -> R.string.ard_upload_kept_playing
            },
            onDismiss,
        )
    }
}

@Composable
private fun Choosing(state: UploadState.Choosing, title: String, onConfirm: (UploadTarget) -> Unit, onDismiss: () -> Unit) {
    val single = state.targets.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ard_upload_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ard_upload_message, title))
                if (state.bytes > 0) Text(stringResource(R.string.ard_upload_size, formatBytes(state.bytes)), modifier = Modifier.padding(top = 8.dp))
                if (state.missingParts > 0) {
                    Text(pluralStringResource(R.plurals.ard_upload_missing_parts, state.missingParts, state.missingParts), modifier = Modifier.padding(top = 8.dp))
                }
                if (single != null) {
                    Text(
                        stringResource(R.string.ard_upload_target, single.serverName, single.libraryName),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(stringResource(R.string.ard_upload_choose), modifier = Modifier.padding(top = 8.dp))
                    state.targets.forEach { target ->
                        ListItem(
                            headlineContent = { Text("${target.serverName} · ${target.libraryName}") },
                            supportingContent = target.folderPath?.let { { Text(it) } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable { onConfirm(target) },
                        )
                    }
                }
                Text(stringResource(R.string.ard_upload_hint), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { if (single != null) TextButton(onClick = { onConfirm(single) }) { Text(stringResource(R.string.ard_upload_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Progress(text: String, fraction: Float?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.ard_upload_title)) },
        text = {
            Column {
                Text(text)
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Notice(message: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ard_upload_title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
