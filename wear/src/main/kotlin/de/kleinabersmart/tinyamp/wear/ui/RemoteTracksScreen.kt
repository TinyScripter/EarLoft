package de.kleinabersmart.tinyamp.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.tinyamp.core.wearsync.WearRemoteListKind
import de.kleinabersmart.tinyamp.wear.R
import kotlinx.coroutines.launch

/**
 * Album, Playlist oder Favoriten des Handys: am Handy abspielen oder mit „Auf der Uhr“ herunterladen (Wear-Konzept 17).
 * [onPlayed] wechselt nach dem Start zur Wiedergabe-Ansicht des Handys.
 */
@Composable
fun RemoteTracksScreen(
    kind: WearRemoteListKind,
    refId: Long?,
    onPlayed: () -> Unit,
    viewModel: RemoteTracksViewModel = hiltViewModel<RemoteTracksViewModel, RemoteTracksViewModel.Factory> { it.create(kind, refId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val onWatch by viewModel.onWatch.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Das Handy liefert für Favoriten keinen Namen.
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
