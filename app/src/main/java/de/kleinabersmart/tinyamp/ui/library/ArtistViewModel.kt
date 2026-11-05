package de.kleinabersmart.tinyamp.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.data.repository.LibraryRepository
import de.kleinabersmart.tinyamp.core.model.ArtistDetails
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.asLoadStateOrMissing
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
