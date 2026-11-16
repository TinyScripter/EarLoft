package de.kleinabersmart.earloft.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.wearsync.WearRemoteListKind
import de.kleinabersmart.earloft.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.earloft.wear.download.SelectionItem
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.sync.PhoneRemote
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

/** Favorisierte Alben bzw. Künstler des Handys: Dann führt „Favoriten“ zu einem Untermenü statt gleich zu den Songs. */
data class PhoneFavorites(val albums: Boolean = false, val artists: Boolean = false) {
    val any: Boolean get() = albums || artists
}

/** Das Handy mischt seine ganze Bibliothek; dazu, welche Favoriten es außer Songs hat. */
@HiltViewModel
class PhoneMusicViewModel @Inject constructor(private val remote: PhoneRemote) : ViewModel() {
    val favorites: StateFlow<PhoneFavorites> = remote.library
        .map { PhoneFavorites(albums = !it?.favoriteAlbumIds.isNullOrEmpty(), artists = !it?.favoriteArtistIds.isNullOrEmpty()) }
        .orOnError(PhoneFavorites())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhoneFavorites())

    /** [onStarted] wechselt danach zur Wiedergabe-Ansicht des Handys. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            if (remote.play(WearRemotePlayRequest(WearRemoteListKind.ALL, shuffle = true))) onStarted()
        }
    }
}
