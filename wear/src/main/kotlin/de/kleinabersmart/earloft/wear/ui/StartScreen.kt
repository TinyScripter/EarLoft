package de.kleinabersmart.earloft.wear.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.wear.R
import de.kleinabersmart.earloft.wear.sync.PhoneStatus

/** Where an entry of the start screen leads. */
enum class StartTarget { MUSIC, PODCASTS, BOOKS, STORAGE, PHONE_MUSIC, PHONE_PODCASTS, PHONE_BOOKS }

/**
 * Start screen (Wear concept 11, 14): at the top the watch itself with music, podcasts and books (each only if some of it is on the
 * watch), below it – as long as a phone with the app is connected – the phone as a remote control with its music, its
 * podcasts and books (from there you can also choose what goes onto the watch, Wear concept 17). Without a phone and without content
 * it explains the setup instead (phone missing, app missing).
 */
@Composable
fun StartScreen(
    onNowPlayingClick: (remote: Boolean) -> Unit,
    onClick: (StartTarget) -> Unit,
    viewModel: StartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Whether a phone is connected is not reported by the API as an event.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    StartContent(
        state = state,
        onNowPlayingClick = onNowPlayingClick,
        onClick = onClick,
        onOpenOnPhone = viewModel::openStoreOnPhone,
        onRetry = viewModel::refresh,
    )
}

@Composable
private fun StartContent(
    state: StartUiState,
    onNowPlayingClick: (Boolean) -> Unit,
    onClick: (StartTarget) -> Unit,
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
            if (state.hasContent || state.phoneConnected) {
                header(R.string.start_section_watch, transformationSpec)
                action(R.string.start_current_playback, transformationSpec, onClick = { onNowPlayingClick(false) }, secondary = state.nowPlaying)
                // Only one entry per kind if some of it is on the watch (even if it is still downloading).
                if (state.hasMusic) action(R.string.list_music, transformationSpec, onClick = { onClick(StartTarget.MUSIC) })
                if (state.hasEpisodes) action(R.string.list_podcasts, transformationSpec, onClick = { onClick(StartTarget.PODCASTS) })
                if (state.hasBooks) action(R.string.list_books, transformationSpec, onClick = { onClick(StartTarget.BOOKS) })
                if (!state.hasContent) message(R.string.list_empty_watch, transformationSpec)
                action(R.string.storage_title, transformationSpec, onClick = { onClick(StartTarget.STORAGE) })
                if (state.phoneConnected) {
                    header(R.string.start_section_phone, transformationSpec)
                    action(R.string.start_current_playback, transformationSpec, onClick = { onNowPlayingClick(true) }, secondary = state.phoneNowPlaying)
                    // Only the sections enabled on the phone (General → Sections).
                    if (SpokenMode.MUSIC in state.areas) action(R.string.list_music, transformationSpec, onClick = { onClick(StartTarget.PHONE_MUSIC) })
                    if (SpokenMode.PODCASTS in state.areas) action(R.string.list_podcasts, transformationSpec, onClick = { onClick(StartTarget.PHONE_PODCASTS) })
                    if (SpokenMode.BOOKS in state.areas) action(R.string.list_books, transformationSpec, onClick = { onClick(StartTarget.PHONE_BOOKS) })
                } else if (state.phone == PhoneStatus.NotConnected) {
                    // The section stays so that the remote control does not disappear without explanation.
                    header(R.string.start_section_phone, transformationSpec)
                    message(R.string.remote_unreachable, transformationSpec)
                    // As the last element the hint would sit at the round edge and be cut off; the spacing lets it scroll up further.
                    item { Spacer(Modifier.height(LIST_END_SPACE)) }
                }
            } else {
                setup(state, transformationSpec, onOpenOnPhone, onRetry)
            }
        }
    }
}

/**
 * Space after the last element of a short list: without it the last element stays squeezed at the round bottom edge
 * (small watches, large font) because the list cannot scroll any further.
 */
internal val LIST_END_SPACE = 48.dp

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
            action(R.string.setup_retry, transformationSpec, onClick = onRetry)
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
        // With a phone the start screen shows the menu, not the setup.
        is PhoneStatus.Connected -> Unit
    }
}

internal fun TransformingLazyColumnScope.header(textRes: Int, transformationSpec: TransformationSpec) = item {
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

internal fun TransformingLazyColumnScope.message(textRes: Int, transformationSpec: TransformationSpec) = item {
    Text(
        text = stringResource(textRes),
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal fun TransformingLazyColumnScope.action(
    textRes: Int,
    transformationSpec: TransformationSpec,
    enabled: Boolean = true,
    secondary: String? = null,
    onClick: () -> Unit,
) = item {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .transformedHeight(this, transformationSpec),
        transformation = SurfaceTransformation(transformationSpec),
        label = { Text(stringResource(textRes)) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    )
}

private val previewCallbacks: @Composable (StartUiState) -> Unit = { state ->
    MaterialTheme {
        StartContent(
            state = state,
            onNowPlayingClick = {},
            onClick = {},
            onOpenOnPhone = {},
            onRetry = {},
        )
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
private fun StartAppMissingPreview() = previewCallbacks(StartUiState(phone = PhoneStatus.AppMissing("phone")))

@WearPreviewDevices
@Composable
private fun StartPhoneConnectedPreview() = previewCallbacks(
    StartUiState(phone = PhoneStatus.Connected("phone"), phoneNowPlaying = "Moonlight"),
)

@WearPreviewDevices
@WearPreviewFontScales
@Composable
private fun StartSelectionsPreview() = previewCallbacks(
    StartUiState(
        phone = PhoneStatus.Connected("phone"),
        hasMusic = true,
    ),
)
