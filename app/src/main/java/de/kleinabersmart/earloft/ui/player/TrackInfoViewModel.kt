package de.kleinabersmart.earloft.ui.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.model.SongSourceInfo
import de.kleinabersmart.earloft.core.playback.PlayedFrom
import de.kleinabersmart.earloft.core.playback.PlayingSources
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** What is shown for a song: its sources and where playback is currently taking it from (`null`: not loaded yet). */
data class TrackInfo(val sources: List<SongSourceInfo>, val playedFrom: PlayedFrom?)

@HiltViewModel
class TrackInfoViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playingSources: PlayingSources,
) : ViewModel() {
    fun info(songId: Long): Flow<TrackInfo> =
        // Without readable sources, at least where playback takes the song from remains.
        combine(flow { emit(libraryRepository.sourcesOf(songId)) }.orOnError(emptyList()), playingSources.bySong) { sources, played ->
            TrackInfo(sources, played[songId])
        }
}
