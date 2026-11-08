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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Aktionen des Album-Menüs (Langklick auf eine Album-Kachel oder -Zeile); die Songs werden erst bei Bedarf geladen. */
@HiltViewModel
class AlbumMenuViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    /** Download-Zustand des Albums; nur beobachten, solange das Menü offen ist. */
    fun offline(albumId: Long): Flow<AlbumOfflineStatus> = downloadRepository.albumStatus(albumId)

    fun playNext(albumId: Long) = withSongs(albumId, playbackConnection::playNext)

    fun addToQueue(albumId: Long) = withSongs(albumId, playbackConnection::addToQueue)

    /** Die Songs des Albums für „Zur Playlist hinzufügen“. */
    suspend fun songIds(albumId: Long): List<Long> = songs(albumId).map { it.id }

    fun download(albumId: Long) {
        viewModelScope.launch { downloadRepository.downloadAlbum(albumId) }
    }

    fun removeDownload(albumId: Long) {
        viewModelScope.launch { downloadRepository.removeAlbum(albumId) }
    }

    // viewModelScope läuft auf dem Main-Thread, wie es PlaybackConnection verlangt.
    private fun withSongs(albumId: Long, action: (List<Song>) -> Unit) {
        viewModelScope.launch { songs(albumId).takeIf { it.isNotEmpty() }?.let(action) }
    }

    private suspend fun songs(albumId: Long): List<Song> = libraryRepository.album(albumId).first()?.songs.orEmpty()
}
