package de.kleinabersmart.earloft.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.core.wearsync.WearRemoteListKind
import de.kleinabersmart.earloft.wear.R
import kotlinx.coroutines.launch

/**
 * Album, playlist or favorites of the phone: play on the phone or download with "On the watch" (Wear concept 17).
 * [onPlayed] switches to the phone's now playing view after starting. [selectable] `false`: without the "Download" button – the
 * favorite songs from the favorites menu, whose button also downloads the favorite albums (Review V17).
 */
@Composable
fun RemoteTracksScreen(
    kind: WearRemoteListKind,
    refId: Long?,
    onPlayed: () -> Unit,
    selectable: Boolean = true,
    viewModel: RemoteTracksViewModel = hiltViewModel<RemoteTracksViewModel, RemoteTracksViewModel.Factory> { it.create(kind, refId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onWatch = if (selectable) viewModel.onWatch.collectAsStateWithLifecycle().value else null
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The phone provides no name for favorites.
    val shown = (state as? TrackListState.Ready)
        ?.takeIf { kind == WearRemoteListKind.FAVORITES && it.title.isEmpty() }
        ?.copy(title = stringResource(R.string.list_favorites))
        ?: state
    TrackListContent(
        state = shown,
        onPlay = { key, shuffle ->
            scope.launch { if (viewModel.play(key?.toInt(), shuffle)) onPlayed() }
        },
        playLabel = R.string.remote_play_on_phone,
        onWatch = onWatch,
        onToggleWatch = viewModel::setOnWatch,
    )
}
