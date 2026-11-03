package keinabersmart.kleinaberlaut.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.wear.WearSelectionRepository
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearQuality
import keinabersmart.kleinaberlaut.core.wearsync.WearStatusDto
import keinabersmart.kleinaberlaut.wear.WatchSettings
import keinabersmart.kleinaberlaut.wear.WatchSettingsRepository
import keinabersmart.kleinaberlaut.wear.WatchStatusRepository
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

data class WatchSettingsUiState(
    val settings: WatchSettings = WatchSettings(),
    /** `null` = wird geladen. */
    val selections: List<WatchSelectionItem>? = null,
    /** Letzter Bericht der Uhr; `null`, solange sie nichts gemeldet hat. */
    val status: WearStatusDto? = null,
)

@HiltViewModel
class WatchSettingsViewModel @Inject constructor(
    private val watchSettings: WatchSettingsRepository,
    private val selections: WearSelectionRepository,
    watchStatus: WatchStatusRepository,
) : ViewModel() {

    val state: StateFlow<WatchSettingsUiState> = combine(
        watchSettings.settings,
        selections.content(),
        watchStatus.status,
    ) { settings, content, status ->
        WatchSettingsUiState(
            settings = settings,
            selections = content.selections.map { WatchSelectionItem(it.kind, it.refId, it.title, it.songIds.size) },
            status = status,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchSettingsUiState())

    fun setQuality(quality: WearQuality) = launch { watchSettings.setQuality(quality) }

    fun setStorageLimitGb(gigabytes: Int?) = launch { watchSettings.setStorageLimitGb(gigabytes) }

    fun setRequireCharging(requireCharging: Boolean) = launch { watchSettings.setRequireCharging(requireCharging) }

    fun remove(item: WatchSelectionItem) = launch { selections.setSelected(item.kind, item.refId, selected = false) }

    fun clear() = launch { selections.clear() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
