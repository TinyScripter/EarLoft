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
import de.kleinabersmart.earloft.wear.sync.PhoneSelections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The music on the watch by selections from the phone (Wear concept 11). */
data class MusicUiState(val selections: List<SelectionItem> = emptyList()) {
    /** The device playlist of the favorites on the watch; `null` as long as the favorites have not been transferred. */
    val favoritesPlaylistId: Long? get() = selections.firstOrNull { it.kind == WearSelectionKind.FAVORITES }?.playlistId

    /** Without downloaded songs there is nothing to shuffle. */
    val canShuffleAll: Boolean get() = selections.any { it.downloadedCount > 0 }
}

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val storage: WatchStorageRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {
    // Without readable selections the menu stays usable, just without "Shuffle all" and favorites.
    val state: StateFlow<MusicUiState> = storage.selections()
        .map { MusicUiState(it) }
        .orOnError(MusicUiState())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    /** Shuffles all downloaded songs; [onStarted] then switches to the now playing view. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            val songs = storage.downloadedSongs()
            if (songs.isEmpty()) return@launch
            playback.play(songs, shuffle = true)
            onStarted()
        }
    }
}

/** Favorited albums or artists of the phone: then "Favorites" leads to a submenu instead of straight to the songs. */
data class PhoneFavorites(val albums: Boolean = false, val artists: Boolean = false) {
    val any: Boolean get() = albums || artists
}

private fun PhoneRemote.favorites(): Flow<PhoneFavorites> = library
    .map { PhoneFavorites(albums = !it?.favoriteAlbumIds.isNullOrEmpty(), artists = !it?.favoriteArtistIds.isNullOrEmpty()) }
    .orOnError(PhoneFavorites())

/** The phone shuffles its whole library; plus which favorites it has besides songs. */
@HiltViewModel
class PhoneMusicViewModel @Inject constructor(private val remote: PhoneRemote) : ViewModel() {
    val favorites: StateFlow<PhoneFavorites> = remote.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhoneFavorites())

    /** [onStarted] then switches to the phone's now playing view. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            if (remote.play(WearRemotePlayRequest(WearRemoteListKind.ALL, shuffle = true))) onStarted()
        }
    }
}

/**
 * The phone's favorites menu: which favorites it has besides songs, and the "Download" button for the favorites selection. It downloads
 * favorite songs and all songs of the favorite albums; that is why the button is here and not on the songs page (Review V17).
 */
@HiltViewModel
class PhoneFavoritesViewModel @Inject constructor(
    remote: PhoneRemote,
    storage: WatchStorageRepository,
    phoneSelections: PhoneSelections,
) : ViewModel() {
    val favorites: StateFlow<PhoneFavorites> = remote.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhoneFavorites())

    private val selection = SelectionOnWatch(WearSelectionKind.FAVORITES, null, storage, phoneSelections, viewModelScope)

    val onWatch: StateFlow<OnWatchState?> = selection.state

    fun setOnWatch(selected: Boolean) = selection.set(selected)
}
