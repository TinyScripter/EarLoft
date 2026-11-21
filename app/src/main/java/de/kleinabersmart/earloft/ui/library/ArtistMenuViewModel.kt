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

/** Actions of the [ArtistMenu]; the songs are only loaded when needed. */
@HiltViewModel
class ArtistMenuViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    /** Download state of the artist's albums; only observe while the menu is open. */
    fun offline(artistId: Long): Flow<AlbumOfflineStatus> = downloadRepository.artistStatus(artistId)

    fun playNext(artistId: Long) = withSongs(artistId, playbackConnection::playNext)

    fun addToQueue(artistId: Long) = withSongs(artistId, playbackConnection::addToQueue)

    /** The artist's songs for "Add to playlist". */
    suspend fun songIds(artistId: Long): List<Long> = libraryRepository.songsOfArtist(artistId).map { it.id }

    fun download(artistId: Long) {
        viewModelScope.launch { downloadRepository.downloadArtist(artistId) }
    }

    fun removeDownload(artistId: Long) {
        viewModelScope.launch { downloadRepository.removeArtist(artistId) }
    }

    // viewModelScope runs on the main thread, as PlaybackConnection requires.
    private fun withSongs(artistId: Long, action: (List<Song>) -> Unit) {
        viewModelScope.launch { libraryRepository.songsOfArtist(artistId).takeIf { it.isNotEmpty() }?.let(action) }
    }
}
