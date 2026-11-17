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
 * Album, Playlist oder Favoriten des Handys: am Handy abspielen oder mit „Auf der Uhr“ herunterladen (Wear-Konzept 17).
 * [onPlayed] wechselt nach dem Start zur Wiedergabe-Ansicht des Handys. [selectable] `false`: ohne Knopf „Download“ – die
 * Favoriten-Songs aus dem Favoriten-Menü, dessen Knopf auch die Lieblingsalben lädt (Review V17).
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
