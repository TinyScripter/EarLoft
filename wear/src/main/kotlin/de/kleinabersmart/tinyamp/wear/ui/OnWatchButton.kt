package de.kleinabersmart.tinyamp.wear.ui

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
import de.kleinabersmart.tinyamp.wear.R

enum class OnWatchStatus { NOT_ON_WATCH, DOWNLOADING, ON_WATCH, REMOVING }

/**
 * Knopf „Download“ einer Liste, Folge oder eines Buchs des Handys (Wear-Konzept 17). [progress]: geladener Anteil 0–1 bei
 * [OnWatchStatus.DOWNLOADING], `null`, solange die Auswahl noch nicht auf der Uhr angekommen ist; [failed]: Das Handy war
 * beim letzten Tipp nicht erreichbar; [downloadFailed]: Etwas ließ sich nicht laden (der Knopf bliebe sonst ohne Erklärung
 * unter 100 %).
 */
data class OnWatchState(
    val status: OnWatchStatus,
    val progress: Float? = null,
    val failed: Boolean = false,
    val downloadFailed: Boolean = false,
)

/**
 * Stand des Knopfs aus der Auswahl der Uhr ([confirmed]: laut Manifest auf der Uhr), dem gewünschten Stand nach einem Tipp
 * ([wanted], bis das Manifest ihn bestätigt) und dem geladenen Anteil.
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
 * „Download“, solange es nicht auf der Uhr ist; beim Laden „Downloading 23 %“; danach „Remove“. Auch während des Ladens
 * führt ein Tipp zu [onRemove] (Abbrechen); die Rückfrage stellt der Aufrufer ([RemoveConfirmDialog]).
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
    // Höchstens 99 %, solange noch etwas fehlt.
    OnWatchStatus.DOWNLOADING -> progress?.let { stringResource(R.string.remote_downloading_percent, (it * 100).toInt().coerceAtMost(99)) }
        ?: stringResource(R.string.remote_downloading)
    OnWatchStatus.ON_WATCH -> stringResource(R.string.remote_remove)
    OnWatchStatus.REMOVING -> stringResource(R.string.storage_removing)
}

/** Unter dem Knopf: Handy beim Tippen nicht erreichbar bzw. Download fehlgeschlagen. */
@Composable
private fun OnWatchState.secondaryText(): String? = when {
    failed -> stringResource(R.string.remote_on_watch_failed)
    downloadFailed -> stringResource(R.string.remote_download_failed)
    else -> null
}

/** Rückfrage vor dem Entfernen von der Uhr („‚[name]‘ entfernen?“), wie in der Speicher-Ansicht. */
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
