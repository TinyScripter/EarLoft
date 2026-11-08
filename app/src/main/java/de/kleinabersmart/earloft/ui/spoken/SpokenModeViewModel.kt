package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.sync.LibraryRefresher
import de.kleinabersmart.earloft.core.model.SpokenMode
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

    /** Die eingeschalteten Bereiche (Allgemein → Bereiche) in der Reihenfolge des Schalters; nie leer. */
    val areas: StateFlow<List<SpokenMode>> = settings.areas.stateIn(viewModelScope, SharingStarted.Eagerly, settings.lastAreas)

    /**
     * Gespeicherter Bereich und eingeschaltete Bereiche, `null`, bis beides gelesen ist; so beginnt der Bereichs-Pager gleich
     * auf der richtigen Seite und mit den richtigen Seiten.
     */
    val loaded: StateFlow<LoadedModes?> = combine(settings.mode, settings.areas, ::LoadedModes)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

/** Zu [SpokenModeViewModel.loaded]: der gezeigte Bereich [mode] ist immer einer der [areas]. */
data class LoadedModes(val mode: SpokenMode, val areas: List<SpokenMode>)
