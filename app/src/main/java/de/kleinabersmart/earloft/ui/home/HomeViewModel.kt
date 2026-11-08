package de.kleinabersmart.earloft.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val recentlyAdded: List<Album> = emptyList(),
    val rediscover: List<Album> = emptyList(),
    val recentlyPlayed: List<Album> = emptyList(),
    val randomArtists: List<Artist> = emptyList(),
    val randomAlbums: List<Album> = emptyList(),
    val isSyncing: Boolean = false,
    val isLoading: Boolean = true,
    val isFailed: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && !isFailed && recentlyAdded.isEmpty() && randomAlbums.isEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    librarySyncer: LibrarySyncer,
) : ViewModel() {

    // Verschachtelt statt über das Array-combine: Eine vertauschte Reihenfolge fällt so schon beim Kompilieren auf.
    val state: StateFlow<HomeUiState> = combine(
        combine(
            libraryRepository.recentlyAddedAlbums(),
            libraryRepository.rediscoverAlbums(),
            libraryRepository.recentlyPlayedAlbums(),
            ::Triple,
        ),
        combine(libraryRepository.randomArtists(), libraryRepository.randomAlbums(), ::Pair),
        librarySyncer.activeSyncs,
    ) { (added, rediscover, played), (artists, albums), syncs ->
        HomeUiState(
            recentlyAdded = added,
            rediscover = rediscover,
            recentlyPlayed = played,
            randomArtists = artists,
            randomAlbums = albums,
            isSyncing = syncs > 0,
            isLoading = false,
        )
    }
        .orOnError(HomeUiState(isLoading = false, isFailed = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
