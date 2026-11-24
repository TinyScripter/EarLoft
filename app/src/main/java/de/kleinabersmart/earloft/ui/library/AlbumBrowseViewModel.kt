package de.kleinabersmart.earloft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.AlbumColorRepository
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.model.AlbumGridSize
import de.kleinabersmart.earloft.core.model.AlbumView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The chosen album view ([AlbumBrowser]) and tile size, and for the record crate the cover color of the front album. */
@HiltViewModel
class AlbumBrowseViewModel @Inject constructor(
    private val librarySettings: LibrarySettings,
    private val albumColorRepository: AlbumColorRepository,
) : ViewModel() {

    /** `null` until the stored view is read, so the grid doesn't flash up before another view. */
    val view: StateFlow<AlbumView?> = librarySettings.albumView
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** `null` until read, like [view]. */
    val gridSize: StateFlow<AlbumGridSize?> = librarySettings.albumGridSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setView(view: AlbumView) {
        viewModelScope.launch { librarySettings.setAlbumView(view) }
    }

    fun setGridSize(size: AlbumGridSize) {
        viewModelScope.launch { librarySettings.setAlbumGridSize(size) }
    }

    /** Seed color of the album's cover (computed once and stored, see [AlbumColorRepository]). */
    fun seedColor(albumId: Long): Flow<Int?> = albumColorRepository.seedColor(albumId)
}
