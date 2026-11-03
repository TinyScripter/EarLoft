package keinabersmart.kleinaberlaut.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySyncer
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.ui.orOnError
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

    val state: StateFlow<HomeUiState> = combine(
        libraryRepository.recentlyAddedAlbums(),
        libraryRepository.rediscoverAlbums(),
        libraryRepository.recentlyPlayedAlbums(),
        libraryRepository.randomArtists(),
        libraryRepository.randomAlbums(),
        librarySyncer.activeSyncs,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            recentlyAdded = values[0] as List<Album>,
            rediscover = values[1] as List<Album>,
            recentlyPlayed = values[2] as List<Album>,
            randomArtists = values[3] as List<Artist>,
            randomAlbums = values[4] as List<Album>,
            isSyncing = (values[5] as Int) > 0,
            isLoading = false,
        )
    }
        .orOnError(HomeUiState(isLoading = false, isFailed = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
