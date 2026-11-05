package de.kleinabersmart.tinyamp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.data.wear.WatchSpoken
import de.kleinabersmart.tinyamp.core.data.wear.WearSelectionRepository
import de.kleinabersmart.tinyamp.core.model.WearSelectionKind
import de.kleinabersmart.tinyamp.core.wearsync.WearQuality
import de.kleinabersmart.tinyamp.core.wearsync.WearStatusDto
import de.kleinabersmart.tinyamp.wear.WatchSettings
import de.kleinabersmart.tinyamp.wear.WatchSettingsRepository
import de.kleinabersmart.tinyamp.wear.WatchStatusRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WatchSelectionItem(
    val kind: WearSelectionKind,
    val refId: Long?,
    val title: String,
    val songCount: Int,
)

/** Eine einzeln gewählte Folge bzw. ein Buch (Wear-Konzept 16.1/16.2); [subtitle] ist der Podcast bzw. der Autor. */
data class WatchEpisodeItem(
    val bookId: Long,
    val title: String,
    val subtitle: String,
    val isBook: Boolean = false,
)

data class WatchSettingsUiState(
    val settings: WatchSettings = WatchSettings(),
    /** `null` = wird geladen. */
    val selections: List<WatchSelectionItem>? = null,
    val episodes: List<WatchEpisodeItem> = emptyList(),
    /** Letzter Bericht der Uhr; `null`, solange sie nichts gemeldet hat. */
    val status: WearStatusDto? = null,
)

@HiltViewModel
class WatchSettingsViewModel @Inject constructor(
    private val watchSettings: WatchSettingsRepository,
    private val selections: WearSelectionRepository,
    private val episodes: WatchSpoken,
    watchStatus: WatchStatusRepository,
) : ViewModel() {

    val state: StateFlow<WatchSettingsUiState> = combine(
        watchSettings.settings,
        selections.content(),
        episodes.content(),
        watchStatus.status,
    ) { settings, content, podcasts, status ->
        val feedTitles = podcasts.feeds.associate { it.feedId to it.title }
        WatchSettingsUiState(
            settings = settings,
            selections = content.selections.map { WatchSelectionItem(it.kind, it.refId, it.title, it.songIds.size) },
            episodes = podcasts.episodes.map { WatchEpisodeItem(it.bookId, it.title, feedTitles[it.feedId].orEmpty()) } +
                podcasts.books.map { WatchEpisodeItem(it.bookId, it.title, it.author, isBook = true) },
            status = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchSettingsUiState())

    fun setQuality(quality: WearQuality) = launch { watchSettings.setQuality(quality) }

    fun setStorageLimitGb(gigabytes: Int?) = launch { watchSettings.setStorageLimitGb(gigabytes) }

    fun setRequireCharging(requireCharging: Boolean) = launch { watchSettings.setRequireCharging(requireCharging) }

    fun remove(item: WatchSelectionItem) = launch { selections.setSelected(item.kind, item.refId, selected = false) }

    fun removeEpisode(item: WatchEpisodeItem) = launch { episodes.setSelected(item.bookId, selected = false) }

    fun clear() = launch {
        selections.clear()
        episodes.clear()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
