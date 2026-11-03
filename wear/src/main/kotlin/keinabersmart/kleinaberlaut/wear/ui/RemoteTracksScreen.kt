package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.wear.R
import kotlinx.coroutines.launch

/** [onPlayed] wechselt nach dem Start zur Wiedergabe-Ansicht des Handys. */
@Composable
fun RemoteTracksScreen(
    kind: WearRemoteListKind,
    refId: Long?,
    onPlayed: () -> Unit,
    viewModel: RemoteTracksViewModel = hiltViewModel<RemoteTracksViewModel, RemoteTracksViewModel.Factory> { it.create(kind, refId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Das Handy liefert für Favoriten keinen Namen.
    val shown = (state as? TrackListState.Ready)
        ?.takeIf { kind == WearRemoteListKind.FAVORITES && it.title.isEmpty() }
        ?.copy(title = stringResource(R.string.add_music_favorites))
        ?: state
    TrackListContent(
        state = shown,
        onPlay = { key, shuffle ->
            scope.launch { if (viewModel.play(key?.toInt(), shuffle)) onPlayed() }
        },
    )
}
