package de.kleinabersmart.earloft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.stats.MusicStatsRepository
import de.kleinabersmart.earloft.core.model.MusicStats
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MusicStatsViewModel @Inject constructor(
    repository: MusicStatsRepository,
    private val libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {
    private val year = MutableStateFlow<Int?>(null)

    /** `null`, solange die Zahlen noch geladen werden. */
    val stats: StateFlow<MusicStats?> =
        year.flatMapLatest { repository.musicStats(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Zeigt die Zahlen dieses Jahres; `null` für „Gesamt“. */
    fun selectYear(value: Int?) {
        year.value = value
    }

    /** Spielt die meistgehörten Songs ab dem angetippten. */
    fun playTopSongs(songIds: List<Long>, songId: Long) {
        viewModelScope.launch {
            val songs = libraryRepository.songs(songIds)
            val index = songs.indexOfFirst { it.id == songId }
            if (index >= 0) playbackConnection.play(songs, startIndex = index)
        }
    }
}
