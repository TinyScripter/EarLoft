package keinabersmart.kleinaberlaut.wear.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import keinabersmart.kleinaberlaut.core.model.DownloadOverview
import keinabersmart.kleinaberlaut.wear.R

/** Speicher & Downloads (Wear-Konzept 11): belegt/frei/Grenze, Fortschritt, Hinweise und „Erneut versuchen“. */
@Composable
fun StorageScreen(viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageContent(state, onRetry = viewModel::retryFailed)
}

@Composable
private fun StorageContent(state: StorageUiState, onRetry: () -> Unit) {
    val context = LocalContext.current
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
            line(transformationSpec) {
                when {
                    overview.pendingSongs > 0 && progress != null ->
                        stringResource(R.string.storage_loading) + " " + (progress * 100).toInt() + " %"
                    overview.pendingSongs > 0 ->
                        pluralStringResource(R.plurals.storage_pending, overview.pendingSongs, overview.pendingSongs)
                    else -> stringResource(R.string.storage_idle)
                }
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
                state.lastError?.let { error -> line(transformationSpec) { stringResource(R.string.storage_last_error, error) } }
                item {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(stringResource(R.string.storage_retry)) },
                    )
                }
            }
        }
    }
}

private fun androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.line(
    transformationSpec: androidx.wear.compose.material3.lazy.TransformationSpec,
    header: Boolean = false,
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
                lastError = "Server nicht erreichbar",
            ),
            onRetry = {},
        )
    }
}
