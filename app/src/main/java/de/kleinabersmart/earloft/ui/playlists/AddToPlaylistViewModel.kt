package de.kleinabersmart.earloft.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.data.repository.ServerRepository
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.ServerInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    serverRepository: ServerRepository,
) : ViewModel() {

    /** `null` while still loading. */
    val playlists: StateFlow<List<Playlist>?> = playlistRepository.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val servers: StateFlow<List<ServerInfo>?> = serverRepository.servers()
        .map { list -> list.filter { it.enabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch { playlistRepository.addSongs(playlistId, songIds) }
    }

    fun create(name: String, serverSourceId: String?, songIds: List<Long>) {
        viewModelScope.launch { playlistRepository.create(name, serverSourceId, songIds) }
    }
}
