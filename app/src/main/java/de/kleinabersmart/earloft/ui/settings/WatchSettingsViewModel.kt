package de.kleinabersmart.earloft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.wear.WatchSpoken
import de.kleinabersmart.earloft.core.data.wear.WearSelectionRepository
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.core.wearsync.WearStatusDto
import de.kleinabersmart.earloft.wear.WatchSettings
import de.kleinabersmart.earloft.wear.WatchSettingsRepository
import de.kleinabersmart.earloft.wear.WatchStatusRepository
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

/** A single selected episode or book (Wear concept 16.1/16.2); [subtitle] is the podcast or the author. */
data class WatchEpisodeItem(
    val bookId: Long,
    val title: String,
    val subtitle: String,
    val isBook: Boolean = false,
)

data class WatchSettingsUiState(
    val settings: WatchSettings = WatchSettings(),
    /** `null` = loading. */
    val selections: List<WatchSelectionItem>? = null,
    val episodes: List<WatchEpisodeItem> = emptyList(),
    /** The watch's last report; `null` as long as it hasn't reported anything. */
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
