package de.kleinabersmart.kleinaberlaut.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import de.kleinabersmart.kleinaberlaut.core.model.ArtistDetails
import de.kleinabersmart.kleinaberlaut.ui.LoadState
import de.kleinabersmart.kleinaberlaut.ui.asLoadStateOrMissing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = ArtistViewModel.Factory::class)
class ArtistViewModel @AssistedInject constructor(
    @Assisted artistId: Long,
    libraryRepository: LibraryRepository,
) : ViewModel() {

    val artist: StateFlow<LoadState<ArtistDetails>> = libraryRepository.artist(artistId)
        .asLoadStateOrMissing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    @AssistedFactory
    interface Factory {
        fun create(artistId: Long): ArtistViewModel
    }
}
