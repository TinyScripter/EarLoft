package keinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenSettings
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.model.SpokenMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpokenModeViewModel @Inject constructor(
    private val settings: SpokenSettings,
    private val refresher: SpokenRefresher,
    private val podcastRefresher: PodcastRefresher,
    private val libraryRefresher: LibraryRefresher,
) : ViewModel() {
    val mode: StateFlow<SpokenMode> = settings.mode.stateIn(viewModelScope, SharingStarted.Eagerly, SpokenMode.MUSIC)

    /** „Überall suchen“ statt nur im gewählten Bereich (Schalter auf der Such-Seite, bleibt gespeichert). */
    val searchAll: StateFlow<Boolean> = settings.searchAll.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setSearchAll(enabled: Boolean) {
        viewModelScope.launch { settings.setSearchAll(enabled) }
    }

    fun setMode(mode: SpokenMode) {
        viewModelScope.launch { settings.setMode(mode) }
    }

    /** Läuft gerade ein von Hand angestoßener Abgleich des aktuellen Modus? */
    val refreshing: StateFlow<Boolean> = combine(
        mode,
        libraryRefresher.refreshing,
        refresher.refreshing,
        podcastRefresher.refreshing,
    ) { mode, music, books, podcasts ->
        when (mode) {
            SpokenMode.MUSIC -> music
            SpokenMode.BOOKS -> books
            SpokenMode.PODCASTS -> podcasts
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun refreshMusic() = libraryRefresher.refreshAll()

    fun refreshBooks() = refresher.refreshAll()

    fun refreshPodcasts() = podcastRefresher.refreshAll()
}
