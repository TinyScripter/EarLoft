package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.sync.PhoneStatus

/**
 * Startbildschirm: die Auswahlen vom Handy (Wear-Konzept 11). Solange es keine gibt, erklärt er die
 * Einrichtung (Handy fehlt, App fehlt, noch nichts übertragen).
 */
@Composable
fun StartScreen(
    onSelectionClick: (playlistId: Long) -> Unit,
    onStorageClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    viewModel: StartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Ob ein Handy verbunden ist, meldet die API nicht als Ereignis.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    StartContent(
        state = state,
        onSelectionClick = onSelectionClick,
        onStorageClick = onStorageClick,
        onNowPlayingClick = onNowPlayingClick,
        onShuffleAll = { viewModel.shuffleAll(onNowPlayingClick) },
        onOpenOnPhone = viewModel::openStoreOnPhone,
        onRetry = viewModel::refresh,
    )
}

@Composable
private fun StartContent(
    state: StartUiState,
    onSelectionClick: (Long) -> Unit,
    onStorageClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onShuffleAll: () -> Unit,
    onOpenOnPhone: (nodeId: String) -> Unit,
    onRetry: () -> Unit,
) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            if (state.hasSelections) {
                header(R.string.app_name, transformationSpec)
                state.nowPlaying?.let { playing ->
                    item {
                        Button(
                            onClick = onNowPlayingClick,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            label = { Text(playing.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = { Text(stringResource(R.string.start_now_playing)) },
                        )
                    }
                }
                items(state.selections, key = { it.playlistId }) { selection ->
                    Button(
                        onClick = { onSelectionClick(selection.playlistId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(selection.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(
                                if (selection.downloadedCount >= selection.songCount) {
                                    pluralStringResource(R.plurals.song_count, selection.songCount, selection.songCount)
                                } else {
                                    stringResource(R.string.selection_progress, selection.downloadedCount, selection.songCount)
                                },
                            )
                        },
                    )
                }
                if (state.canShuffleAll) action(R.string.start_shuffle_all, transformationSpec, onShuffleAll)
                action(R.string.storage_title, transformationSpec, onStorageClick)
            } else {
                setup(state, transformationSpec, onOpenOnPhone, onRetry)
            }
        }
    }
}

private fun TransformingLazyColumnScope.setup(
    state: StartUiState,
    transformationSpec: TransformationSpec,
    onOpenOnPhone: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when (val phone = state.phone) {
        PhoneStatus.Checking -> item {
            CircularProgressIndicator(Modifier.transformedHeight(this, transformationSpec))
        }
        PhoneStatus.NotConnected -> {
            header(R.string.setup_no_phone_title, transformationSpec)
            message(R.string.setup_no_phone_text, transformationSpec)
            action(R.string.setup_retry, transformationSpec, onRetry)
        }
        is PhoneStatus.AppMissing -> {
            header(R.string.setup_app_missing_title, transformationSpec)
            message(R.string.setup_app_missing_text, transformationSpec)
            when (state.openOnPhone) {
                OpenOnPhoneResult.OPENED -> message(R.string.setup_opened_on_phone, transformationSpec)
                OpenOnPhoneResult.FAILED -> message(R.string.setup_open_failed, transformationSpec)
                null -> Unit
            }
            action(R.string.setup_install_on_phone, transformationSpec) { onOpenOnPhone(phone.nodeId) }
        }
        is PhoneStatus.Connected -> {
            header(R.string.setup_waiting_title, transformationSpec)
            message(R.string.setup_waiting_text, transformationSpec)
        }
    }
}

private fun TransformingLazyColumnScope.header(textRes: Int, transformationSpec: TransformationSpec) = item {
    ListHeader(
        modifier = Modifier
            .minimumVerticalContentPadding(
                ListHeaderDefaults.minimumTopListContentPadding,
                ListHeaderDefaults.minimumBottomListContentPadding,
            )
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
    ) {
        Text(stringResource(textRes), textAlign = TextAlign.Center)
    }
}

private fun TransformingLazyColumnScope.message(textRes: Int, transformationSpec: TransformationSpec) = item {
    Text(
        text = stringResource(textRes),
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun TransformingLazyColumnScope.action(textRes: Int, transformationSpec: TransformationSpec, onClick: () -> Unit) =
    item {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .transformedHeight(this, transformationSpec),
            transformation = SurfaceTransformation(transformationSpec),
            label = { Text(stringResource(textRes)) },
        )
    }

@WearPreviewDevices
@WearPreviewFontScales
@Composable
private fun StartAppMissingPreview() {
    MaterialTheme {
        StartContent(
            state = StartUiState(phone = PhoneStatus.AppMissing("phone")),
            onSelectionClick = {},
            onStorageClick = {},
            onNowPlayingClick = {},
            onShuffleAll = {},
            onOpenOnPhone = {},
            onRetry = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun StartWaitingPreview() {
    MaterialTheme {
        StartContent(
            state = StartUiState(phone = PhoneStatus.Connected("phone")),
            onSelectionClick = {},
            onStorageClick = {},
            onNowPlayingClick = {},
            onShuffleAll = {},
            onOpenOnPhone = {},
            onRetry = {},
        )
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
private fun StartSelectionsPreview() {
    MaterialTheme {
        StartContent(
            state = StartUiState(
                phone = PhoneStatus.Connected("phone"),
                selections = listOf(
                    SelectionItem(1, WearSelectionKind.FAVORITES, "Favoriten", 42),
                    SelectionItem(2, WearSelectionKind.ALBUM, "Mondlicht", 4, downloadedCount = 1),
                    SelectionItem(3, WearSelectionKind.PLAYLIST, "Laufen am Morgen mit sehr langem Namen", 1),
                ),
            ),
            onSelectionClick = {},
            onStorageClick = {},
            onNowPlayingClick = {},
            onShuffleAll = {},
            onOpenOnPhone = {},
            onRetry = {},
        )
    }
}
