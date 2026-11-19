package de.kleinabersmart.earloft.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import de.kleinabersmart.earloft.wear.R

enum class OnWatchStatus { NOT_ON_WATCH, DOWNLOADING, ON_WATCH, REMOVING }

/**
 * "Download" button of a phone list, episode or book (Wear concept 17). [progress]: downloaded share 0–1 for
 * [OnWatchStatus.DOWNLOADING], `null` as long as the selection has not yet arrived on the watch; [failed]: the phone was
 * unreachable on the last tap; [downloadFailed]: something could not be downloaded (otherwise the button would stay below
 * 100 % without explanation).
 */
data class OnWatchState(
    val status: OnWatchStatus,
    val progress: Float? = null,
    val failed: Boolean = false,
    val downloadFailed: Boolean = false,
)

/**
 * State of the button from the watch's selection ([confirmed]: on the watch according to the manifest), the desired state after a tap
 * ([wanted], until the manifest confirms it) and the downloaded share.
 */
internal fun onWatchState(
    confirmed: Boolean,
    wanted: Boolean?,
    progress: Float?,
    failed: Boolean,
    downloadFailed: Boolean = false,
): OnWatchState {
    val status = when {
        wanted == false -> OnWatchStatus.REMOVING
        !confirmed -> if (wanted == true) OnWatchStatus.DOWNLOADING else OnWatchStatus.NOT_ON_WATCH
        progress != null && progress < 1f -> OnWatchStatus.DOWNLOADING
        else -> OnWatchStatus.ON_WATCH
    }
    val downloading = confirmed && status == OnWatchStatus.DOWNLOADING
    return OnWatchState(status, progress?.takeIf { downloading }, failed, downloadFailed = downloading && downloadFailed)
}

/**
 * "Download" as long as it is not on the watch; while downloading "Downloading 23 %"; afterwards "Remove". Even while downloading
 * a tap leads to [onRemove] (cancel); the caller asks for confirmation ([RemoveConfirmDialog]).
 */
internal fun TransformingLazyColumnScope.onWatchButton(
    state: OnWatchState,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    transformationSpec: TransformationSpec,
) = item {
    val status = state.status
    Button(
        onClick = if (status == OnWatchStatus.NOT_ON_WATCH) onDownload else onRemove,
        enabled = status != OnWatchStatus.REMOVING,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
        colors = if (status == OnWatchStatus.NOT_ON_WATCH) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        label = { Text(state.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = state.secondaryText()?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    )
}

@Composable
private fun OnWatchState.label(): String = when (status) {
    OnWatchStatus.NOT_ON_WATCH -> stringResource(R.string.remote_download)
    // At most 99 % as long as something is still missing.
    OnWatchStatus.DOWNLOADING -> progress?.let { stringResource(R.string.remote_downloading_percent, (it * 100).toInt().coerceAtMost(99)) }
        ?: stringResource(R.string.remote_downloading)
    OnWatchStatus.ON_WATCH -> stringResource(R.string.remote_remove)
    OnWatchStatus.REMOVING -> stringResource(R.string.storage_removing)
}

/** Below the button: phone unreachable when tapping, or download failed. */
@Composable
private fun OnWatchState.secondaryText(): String? = when {
    failed -> stringResource(R.string.remote_on_watch_failed)
    downloadFailed -> stringResource(R.string.remote_download_failed)
    else -> null
}

/** Confirmation before removing from the watch ("Remove '[name]'?"), as in the storage view. */
@Composable
internal fun RemoveConfirmDialog(visible: Boolean, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = onConfirm) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) },
        title = { Text(stringResource(R.string.storage_remove_title, name), maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = { Text(stringResource(R.string.storage_remove_confirm)) },
    )
}
