package de.kleinabersmart.earloft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.model.AlbumOfflineStatus
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Aktionen des [ArtistMenu]; die Songs werden erst bei Bedarf geladen. */
@HiltViewModel
class ArtistMenuViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    /** Download-Zustand der Alben des Künstlers; nur beobachten, solange das Menü offen ist. */
    fun offline(artistId: Long): Flow<AlbumOfflineStatus> = downloadRepository.artistStatus(artistId)

    fun playNext(artistId: Long) = withSongs(artistId, playbackConnection::playNext)

    fun addToQueue(artistId: Long) = withSongs(artistId, playbackConnection::addToQueue)

    fun download(artistId: Long) {
        viewModelScope.launch { downloadRepository.downloadArtist(artistId) }
    }

    fun removeDownload(artistId: Long) {
        viewModelScope.launch { downloadRepository.removeArtist(artistId) }
    }

    // viewModelScope läuft auf dem Main-Thread, wie es PlaybackConnection verlangt.
    private fun withSongs(artistId: Long, action: (List<Song>) -> Unit) {
        viewModelScope.launch { libraryRepository.songsOfArtist(artistId).takeIf { it.isNotEmpty() }?.let(action) }
    }
}
