package de.kleinabersmart.tinyamp.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.model.WearSelectionKind
import de.kleinabersmart.tinyamp.core.playback.PlaybackConnection
import de.kleinabersmart.tinyamp.core.wearsync.WearRemoteListKind
import de.kleinabersmart.tinyamp.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.tinyamp.wear.download.SelectionItem
import de.kleinabersmart.tinyamp.wear.download.WatchStorageRepository
import de.kleinabersmart.tinyamp.wear.sync.PhoneRemote
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Die Musik auf der Uhr nach Auswahlen vom Handy (Wear-Konzept 11). */
data class MusicUiState(val selections: List<SelectionItem> = emptyList()) {
    /** Die Geräte-Playlist der Favoriten auf der Uhr; `null`, solange die Favoriten nicht übertragen sind. */
    val favoritesPlaylistId: Long? get() = selections.firstOrNull { it.kind == WearSelectionKind.FAVORITES }?.playlistId

    /** Ohne geladene Songs gibt es nichts zu mischen. */
    val canShuffleAll: Boolean get() = selections.any { it.downloadedCount > 0 }
}

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val storage: WatchStorageRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // Ohne lesbare Auswahlen bleibt das Menü bedienbar, nur ohne „Alles zufällig“ und Favoriten.
    val state: StateFlow<MusicUiState> = storage.selections()
        .map { MusicUiState(it) }
        .orOnError(MusicUiState())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    /** Mischt alle geladenen Songs; [onStarted] wechselt danach zur Wiedergabe-Ansicht. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            val songs = storage.downloadedSongs()
            if (songs.isEmpty()) return@launch
            playback.play(songs, shuffle = true)
            onStarted()
        }
    }
}

/** Das Handy mischt seine ganze Bibliothek. */
@HiltViewModel
class PhoneMusicViewModel @Inject constructor(private val remote: PhoneRemote) : ViewModel() {
    /** [onStarted] wechselt danach zur Wiedergabe-Ansicht des Handys. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            if (remote.play(WearRemotePlayRequest(WearRemoteListKind.ALL, shuffle = true))) onStarted()
        }
    }
}
