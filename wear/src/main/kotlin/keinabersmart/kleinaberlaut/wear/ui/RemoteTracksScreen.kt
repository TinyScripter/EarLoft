package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.core.wearsync.WearRemotePlayRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteTrackDto
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.sync.PhoneRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Titelliste eines Albums, einer Playlist oder der Favoriten auf dem Handy (Wear-Konzept 15). */
@HiltViewModel(assistedFactory = RemoteTracksViewModel.Factory::class)
class RemoteTracksViewModel @AssistedInject constructor(
    @Assisted private val kind: WearRemoteListKind,
    @Assisted private val refId: Long?,
    private val remote: PhoneRemote,
) : ViewModel() {

    private val _state = MutableStateFlow<TrackListState>(TrackListState.Loading)
    val state: StateFlow<TrackListState> = _state

    private var tracks: List<WearRemoteTrackDto> = emptyList()

    init {
        viewModelScope.launch {
            val result = remote.tracks(kind, refId)
            _state.value = when {
                result == null -> TrackListState.Message(R.string.remote_unreachable)
                !result.found -> TrackListState.Message(R.string.remote_missing)
                else -> {
                    tracks = result.tracks
                    TrackListState.Ready(
                        title = result.title,
                        songCount = result.tracks.size,
                        loadedCount = null,
                        // Die Position ist der Schlüssel: Ein Titel kann in einer Playlist mehrfach vorkommen.
                        items = result.tracks.mapIndexed { index, track -> TrackItem(index.toLong(), track.title, track.artist) },
                        truncated = result.truncated,
                    )
                }
            }
        }
    }

    /** Das Handy spielt ab dem Titel an Position [index] bzw. von vorn. @return `false`, wenn es nicht erreichbar war. */
    suspend fun play(index: Int?, shuffle: Boolean): Boolean = remote.play(
        WearRemotePlayRequest(
            kind = kind,
            refId = refId,
            startSongId = index?.let { tracks.getOrNull(it)?.songId },
            startIndex = index ?: 0,
            shuffle = shuffle,
        ),
    )

    @AssistedFactory
    interface Factory {
        fun create(kind: WearRemoteListKind, refId: Long?): RemoteTracksViewModel
    }
}

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
