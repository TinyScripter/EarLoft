package keinabersmart.kleinaberlaut.wear.ui

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
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.sync.PhoneStatus

/**
 * Startbildschirm (Wear-Konzept 11, 14): oben die Uhr selbst, darunter – solange ein Handy mit der App verbunden ist –
 * das Handy als Fernsteuerung. Ohne Handy und ohne geladene Musik erklärt er stattdessen die Einrichtung
 * (Handy fehlt, App fehlt).
 */
@Composable
fun StartScreen(
    onStorageClick: () -> Unit,
    onAddMusicClick: () -> Unit,
    onNowPlayingClick: (remote: Boolean) -> Unit,
    onAlbumsClick: (remote: Boolean) -> Unit,
    onPlaylistsClick: (remote: Boolean) -> Unit,
    onFavoritesClick: (playlistId: Long) -> Unit,
    onPhoneFavoritesClick: () -> Unit,
    viewModel: StartViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Ob ein Handy verbunden ist, meldet die API nicht als Ereignis.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    StartContent(
        state = state,
        onStorageClick = onStorageClick,
        onAddMusicClick = onAddMusicClick,
        onNowPlayingClick = onNowPlayingClick,
        onAlbumsClick = onAlbumsClick,
        onPlaylistsClick = onPlaylistsClick,
        onFavoritesClick = onFavoritesClick,
        onPhoneFavoritesClick = onPhoneFavoritesClick,
        onShuffleAll = { viewModel.shuffleAll { onNowPlayingClick(false) } },
        onShuffleAllOnPhone = { viewModel.shuffleAllOnPhone { onNowPlayingClick(true) } },
        onOpenOnPhone = viewModel::openStoreOnPhone,
        onRetry = viewModel::refresh,
    )
}

@Composable
private fun StartContent(
    state: StartUiState,
    onStorageClick: () -> Unit,
    onAddMusicClick: () -> Unit,
    onNowPlayingClick: (Boolean) -> Unit,
    onAlbumsClick: (Boolean) -> Unit,
    onPlaylistsClick: (Boolean) -> Unit,
    onFavoritesClick: (Long) -> Unit,
    onPhoneFavoritesClick: () -> Unit,
    onShuffleAll: () -> Unit,
    onShuffleAllOnPhone: () -> Unit,
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
            if (state.hasSelections || state.phoneConnected) {
                header(R.string.start_section_watch, transformationSpec)
                action(R.string.start_current_playback, transformationSpec, onClick = { onNowPlayingClick(false) }, secondary = state.nowPlaying?.title)
                action(R.string.list_albums, transformationSpec, onClick = { onAlbumsClick(false) })
                val favorites = state.favoritesPlaylistId
                action(R.string.list_favorites, transformationSpec, enabled = favorites != null, onClick = { favorites?.let(onFavoritesClick) })
                action(R.string.list_playlists, transformationSpec, onClick = { onPlaylistsClick(false) })
                if (state.canShuffleAll) action(R.string.start_shuffle_all, transformationSpec, onClick = onShuffleAll)
                action(R.string.add_music_title, transformationSpec, onClick = onAddMusicClick)
                action(R.string.storage_title, transformationSpec, onClick = onStorageClick)
                if (state.phoneConnected) {
                    header(R.string.start_section_phone, transformationSpec)
                    action(R.string.start_current_playback, transformationSpec, onClick = { onNowPlayingClick(true) }, secondary = state.phoneNowPlaying)
                    action(R.string.list_albums, transformationSpec, onClick = { onAlbumsClick(true) })
                    action(R.string.list_favorites, transformationSpec, onClick = onPhoneFavoritesClick)
                    action(R.string.list_playlists, transformationSpec, onClick = { onPlaylistsClick(true) })
                    action(R.string.start_shuffle_all, transformationSpec, onClick = onShuffleAllOnPhone)
                } else if (state.phone == PhoneStatus.NotConnected) {
                    // Der Abschnitt bleibt, damit die Fernsteuerung nicht ohne Erklärung verschwindet.
                    header(R.string.start_section_phone, transformationSpec)
                    message(R.string.remote_unreachable, transformationSpec)
                    // Als letztes Element läge der Hinweis am runden Rand und würde abgeschnitten; der Abstand lässt ihn weiter hochscrollen.
                    item { Spacer(Modifier.height(BOTTOM_HINT_SPACE)) }
                }
            } else {
                setup(state, transformationSpec, onOpenOnPhone, onRetry)
            }
        }
    }
}

private val BOTTOM_HINT_SPACE = 48.dp

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
        // Mit Handy zeigt der Startbildschirm das Menü, nicht die Einrichtung.
        is PhoneStatus.Connected -> Unit
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

private fun TransformingLazyColumnScope.action(
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
            onStorageClick = {},
            onAddMusicClick = {},
            onNowPlayingClick = {},
            onAlbumsClick = {},
            onPlaylistsClick = {},
            onFavoritesClick = {},
            onPhoneFavoritesClick = {},
            onShuffleAll = {},
            onShuffleAllOnPhone = {},
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
    StartUiState(phone = PhoneStatus.Connected("phone"), phoneNowPlaying = "Mondlicht"),
)

@WearPreviewDevices
@WearPreviewFontScales
@Composable
private fun StartSelectionsPreview() = previewCallbacks(
    StartUiState(
        phone = PhoneStatus.Connected("phone"),
        selections = listOf(
            SelectionItem(1, WearSelectionKind.FAVORITES, "Favoriten", 42),
            SelectionItem(2, WearSelectionKind.ALBUM, "Mondlicht", 4, downloadedCount = 1),
            SelectionItem(3, WearSelectionKind.PLAYLIST, "Laufen am Morgen mit sehr langem Namen", 1),
        ),
    ),
)
