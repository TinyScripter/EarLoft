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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Titelliste eines Albums, einer Playlist oder der Favoriten auf dem Handy (Wear-Konzept 15) – zugleich die kleine Seite,
 * auf der man sie am Handy abspielt oder auf die Uhr holt (17).
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

    /** Was die Uhr als Auswahl führt; nur Alben, Playlists und Favoriten lassen sich auf die Uhr holen. */
    private val selectionKind: WearSelectionKind? = when (kind) {
        WearRemoteListKind.ALBUM -> WearSelectionKind.ALBUM
        WearRemoteListKind.PLAYLIST -> WearSelectionKind.PLAYLIST
        WearRemoteListKind.FAVORITES -> WearSelectionKind.FAVORITES
        WearRemoteListKind.ALL, WearRemoteListKind.CONTINUE -> null
    }
    private val selectionKey = selectionKind?.key(refId)

    /**
     * Gewünschter Stand nach einem Tipp, bis das Manifest des Handys ihn bestätigt (oder [PENDING_TIMEOUT_MS] vergeht):
     * Der Knopf spränge sonst bis zum nächsten Manifest zurück.
     */
    private val pending = MutableStateFlow<Boolean?>(null)
    private val failed = MutableStateFlow(false)
    private var pendingTimeout: Job? = null

    val onWatch: StateFlow<OnWatchState?> = if (selectionKey == null) {
        MutableStateFlow(null)
    } else {
        combine(storage.selectedKeys(), storage.selections(), pending, failed) { keys, selections, wanted, failedNow ->
            val confirmed = selectionKey in keys
            if (wanted == confirmed) pending.value = null
            val item = selections.firstOrNull { it.key == selectionKey }
            onWatchState(confirmed, wanted, item?.progress, failedNow, downloadFailed = (item?.failedCount ?: 0) > 0)
        }.orOnError(null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

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

    /** Holt die Liste auf die Uhr bzw. nimmt sie herunter; das Handy wählt aus und schickt ein neues Manifest. */
    fun setOnWatch(selected: Boolean) {
        val selection = selectionKind ?: return
        viewModelScope.launch {
            failed.value = false
            pending.value = selected
            if (!phoneSelections.setSelected(selection, refId, selected)) {
                pending.value = null
                failed.value = true
                return@launch
            }
            pendingTimeout?.cancel()
            pendingTimeout = launch {
                delay(PENDING_TIMEOUT_MS)
                pending.value = null
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(kind: WearRemoteListKind, refId: Long?): RemoteTracksViewModel
    }

    private companion object {
        const val PENDING_TIMEOUT_MS = 20_000L
    }
}
