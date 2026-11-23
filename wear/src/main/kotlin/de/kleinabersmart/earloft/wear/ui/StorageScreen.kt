package de.kleinabersmart.earloft.wear.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import de.kleinabersmart.earloft.core.model.DownloadOverview
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearDownloadError
import de.kleinabersmart.earloft.wear.R
import de.kleinabersmart.earloft.wear.download.estimatePhoneTransferMinutes
import de.kleinabersmart.earloft.wear.download.textRes

/**
 * Storage & downloads (Wear concept 11): used/free/limit, progress, hints and "Retry"; below that albums,
 * episodes and books on the watch, removable individually.
 */
@Composable
fun StorageScreen(viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val removeFailed by viewModel.removeFailed.collectAsStateWithLifecycle()
    StorageContent(
        state,
        entries = entries,
        removeFailed = removeFailed,
        onRetry = viewModel::retryFailed,
        onClear = viewModel::clear,
        onRemove = viewModel::remove,
        onDismissRemoveFailed = viewModel::dismissRemoveFailed,
    )
}

@Composable
private fun StorageContent(
    state: StorageUiState,
    entries: List<StoredEntry>,
    removeFailed: Boolean,
    onRetry: () -> Unit,
    onClear: (StoredSection?) -> Unit,
    onRemove: (StoredEntry) -> Unit,
    onDismissRemoveFailed: () -> Unit,
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    // `null` = everything; stays set so that the dialog keeps its text while fading out.
    var clearSection by remember { mutableStateOf<StoredSection?>(null) }
    // The entry stays set so that the dialog keeps its text while fading out.
    var removeCandidate by remember { mutableStateOf<StoredEntry?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val overview = state.overview ?: DownloadOverview.EMPTY
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            line(transformationSpec, header = true) { stringResource(R.string.storage_title) }
            line(transformationSpec) { stringResource(R.string.storage_used, Formatter.formatFileSize(context, overview.usedBytes)) }
            line(transformationSpec) { stringResource(R.string.storage_free, Formatter.formatFileSize(context, overview.freeBytes)) }
            state.limitBytes?.let { limit ->
                line(transformationSpec) { stringResource(R.string.storage_limit, Formatter.formatFileSize(context, limit)) }
            }
            val progress = overview.currentProgress
            // "All downloaded" only if nothing failed, nothing was left out and no error is pending (review H27: the last waiting
            // file running into a full storage showed it); otherwise the lines below say what is wrong.
            val complete = overview.failedSongs == 0 && state.missingForStorage == 0 && state.lastError == null
            if (overview.pendingSongs > 0 || complete) {
                line(transformationSpec) {
                    when {
                        overview.pendingSongs > 0 && progress != null ->
                            stringResource(R.string.storage_loading_percent, (progress * 100).toInt())
                        overview.pendingSongs > 0 ->
                            pluralStringResource(R.plurals.storage_pending, overview.pendingSongs, overview.pendingSongs)
                        else -> stringResource(R.string.storage_idle)
                    }
                }
            }
            if (state.waitingForCharger) {
                line(transformationSpec) { stringResource(R.string.storage_waiting_charger) }
            }
            if (state.waitingForWifi) {
                line(transformationSpec) { stringResource(R.string.storage_waiting_wifi) }
            }
            if (state.phoneBacklogBytes > 0) {
                line(transformationSpec) {
                    stringResource(
                        R.string.storage_phone_transfer,
                        Formatter.formatFileSize(context, state.phoneBacklogBytes),
                        estimatePhoneTransferMinutes(state.phoneBacklogBytes),
                    )
                }
                line(transformationSpec) { stringResource(R.string.storage_phone_charger_hint) }
            }
            if (state.missingForStorage > 0) {
                line(transformationSpec) {
                    pluralStringResource(R.plurals.storage_missing, state.missingForStorage, state.missingForStorage)
                }
            }
            if (overview.failedSongs > 0) {
                line(transformationSpec) {
                    pluralStringResource(R.plurals.storage_failed, overview.failedSongs, overview.failedSongs)
                }
                state.lastError?.let { error -> line(transformationSpec, maxLines = LAST_ERROR_MAX_LINES) { stringResource(R.string.storage_last_error, stringResource(error.textRes)) } }
                item {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(stringResource(R.string.storage_retry)) },
                    )
                }
            } else {
                // E.g. "Phone unreachable" while the tracks are waiting, or "Storage full" after the last one did not fit.
                state.lastError?.let { error -> line(transformationSpec, maxLines = LAST_ERROR_MAX_LINES) { stringResource(R.string.storage_last_error, stringResource(error.textRes)) } }
            }
            if (removeFailed) {
                line(transformationSpec) { stringResource(R.string.storage_clear_no_phone) }
            }
            item {
                Button(
                    onClick = {
                        onDismissRemoveFailed()
                        clearSection = null
                        confirmClear = true
                    },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(stringResource(R.string.storage_clear_all)) },
                )
            }
            StoredSection.entries.forEach { section ->
                val inSection = entries.filter { it.section == section }
                if (inSection.isEmpty()) return@forEach
                line(transformationSpec, header = true) { stringResource(section.titleRes) }
                // First option per section: remove all of it.
                item(key = "clear:$section") {
                    Button(
                        onClick = {
                            onDismissRemoveFailed()
                            clearSection = section
                            confirmClear = true
                        },
                        enabled = inSection.any { !it.removing },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = inSection.any { !it.removing }),
                        label = { Text(stringResource(section.clearRes)) },
                    )
                }
                items(inSection, key = { it.key }) { entry ->
                    val cover = entry.cover
                    Button(
                        onClick = {
                            onDismissRemoveFailed()
                            removeCandidate = entry
                            confirmRemove = true
                        },
                        enabled = !entry.removing,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = if (cover != null) { { CoverThumbnail(cover) } } else null,
                        label = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text(entry.secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
    RemoveConfirmDialog(
        visible = confirmRemove,
        name = removeCandidate?.title.orEmpty(),
        onConfirm = {
            confirmRemove = false
            removeCandidate?.let(onRemove)
        },
        onDismiss = { confirmRemove = false },
    )
    AlertDialog(
        visible = confirmClear,
        onDismissRequest = { confirmClear = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmClear = false
                onClear(clearSection)
            })
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmClear = false }) },
        title = { Text(stringResource(clearSection?.clearRes ?: R.string.storage_clear_all)) },
        text = { Text(stringResource(clearSection?.clearConfirmRes ?: R.string.storage_clear_confirm)) },
    )
}

/** Otherwise a long error message pushes the remaining time and everything below it off the screen. */
private const val LAST_ERROR_MAX_LINES = 3

private val StoredSection.clearRes: Int
    get() = when (this) {
        StoredSection.MUSIC -> R.string.storage_clear_music
        StoredSection.PODCASTS -> R.string.storage_clear_podcasts
        StoredSection.BOOKS -> R.string.storage_clear_books
    }

private val StoredSection.clearConfirmRes: Int
    get() = when (this) {
        StoredSection.MUSIC -> R.string.storage_clear_music_confirm
        StoredSection.PODCASTS -> R.string.storage_clear_podcasts_confirm
        StoredSection.BOOKS -> R.string.storage_clear_books_confirm
    }

private val StoredSection.titleRes: Int
    get() = when (this) {
        StoredSection.MUSIC -> R.string.list_music
        StoredSection.PODCASTS -> R.string.list_podcasts
        StoredSection.BOOKS -> R.string.list_books
    }

/** Used storage; while still downloading, with a hint about it. */
@Composable
private fun StoredEntry.secondaryText(): String {
    val size = Formatter.formatFileSize(LocalContext.current, bytes)
    return when {
        removing -> stringResource(R.string.storage_removing)
        loaded -> size
        bytes == 0L -> stringResource(R.string.selection_not_loaded)
        else -> stringResource(R.string.storage_entry_loading, size)
    }
}

internal fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.line(
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
    header: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    text: @Composable () -> String,
) = item {
    if (header) {
        ListHeader(
            modifier = Modifier.transformedHeight(this, transformationSpec),
            transformation = SurfaceTransformation(transformationSpec),
        ) {
            Text(text(), textAlign = TextAlign.Center)
        }
    } else {
        Text(
            text(),
            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
private fun StoragePreview() {
    MaterialTheme {
        StorageContent(
            StorageUiState(
                overview = DownloadOverview(12, 5, 2, 420_000_000, 3_100_000_000, 0.4f),
                limitBytes = 2_000_000_000,
                missingForStorage = 3,
                lastError = WearDownloadError.PHONE_UNREACHABLE,
            ),
            entries = listOf(
                StoredEntry("album:1", StoredSection.MUSIC, "Moonlight", 48_000_000, loaded = true, StoredRef.Music(WearSelectionKind.ALBUM, 1)),
                StoredEntry("book:2", StoredSection.PODCASTS, "#383 Drones everywhere", 31_000_000, loaded = true, StoredRef.Spoken("f1", "g1")),
                StoredEntry("book:3", StoredSection.BOOKS, "20,000 Leagues Under the Sea", 120_000_000, loaded = false, StoredRef.Spoken("s1", "r1")),
            ),
            removeFailed = false,
            onRetry = {},
            onClear = {},
            onRemove = {},
            onDismissRemoveFailed = {},
        )
    }
}
