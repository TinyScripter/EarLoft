package de.kleinabersmart.earloft.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearRemoteListKind
import de.kleinabersmart.earloft.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteTrackDto
import de.kleinabersmart.earloft.wear.R
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.sync.PhoneRemote
import de.kleinabersmart.earloft.wear.sync.PhoneSelections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Track list of an album, a playlist or the favorites on the phone (Wear concept 15) – at the same time the small page
 * on which you play it on the phone or bring it to the watch (17).
 */
@HiltViewModel(assistedFactory = RemoteTracksViewModel.Factory::class)
class RemoteTracksViewModel @AssistedInject constructor(
    @Assisted private val kind: WearRemoteListKind,
    @Assisted private val refId: Long?,
    private val remote: PhoneRemote,
    private val phoneSelections: PhoneSelections,
    storage: WatchStorageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TrackListState>(TrackListState.Loading)
    val state: StateFlow<TrackListState> = _state

    private var tracks: List<WearRemoteTrackDto> = emptyList()

    /** What the watch keeps as a selection; only albums, playlists and favorites can be brought to the watch. */
    private val selectionKind: WearSelectionKind? = when (kind) {
        WearRemoteListKind.ALBUM -> WearSelectionKind.ALBUM
        WearRemoteListKind.PLAYLIST -> WearSelectionKind.PLAYLIST
        WearRemoteListKind.FAVORITES -> WearSelectionKind.FAVORITES
        WearRemoteListKind.ALL, WearRemoteListKind.CONTINUE -> null
    }
    private val selection = selectionKind?.let { SelectionOnWatch(it, refId, storage, phoneSelections, viewModelScope) }

    /** "Download" button; `null` for lists that cannot be brought to the watch. */
    val onWatch: StateFlow<OnWatchState?> = selection?.state ?: MutableStateFlow(null)

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
                        // The position is the key: a track can occur several times in a playlist.
                        items = result.tracks.mapIndexed { index, track -> TrackItem(index.toLong(), track.title, track.artist) },
                        truncated = result.truncated,
                    )
                }
            }
        }
    }

    /** The phone plays from the track at position [index] or from the start. @return `false` if it was unreachable. */
    suspend fun play(index: Int?, shuffle: Boolean): Boolean = remote.play(
        WearRemotePlayRequest(
            kind = kind,
            refId = refId,
            startSongId = index?.let { tracks.getOrNull(it)?.songId },
            startIndex = index ?: 0,
            shuffle = shuffle,
        ),
    )

    /** Brings the list to the watch or removes it; the phone selects and sends a new manifest. */
    fun setOnWatch(selected: Boolean) {
        selection?.set(selected)
    }

    @AssistedFactory
    interface Factory {
        fun create(kind: WearRemoteListKind, refId: Long?): RemoteTracksViewModel
    }
}
