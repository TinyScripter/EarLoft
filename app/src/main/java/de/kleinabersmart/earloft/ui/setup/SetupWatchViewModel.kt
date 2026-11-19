package de.kleinabersmart.earloft.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.wear.WatchConnection
import de.kleinabersmart.earloft.wear.WatchSettings
import de.kleinabersmart.earloft.wear.WatchSettingsRepository
import de.kleinabersmart.earloft.wear.WatchSetup
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The watch in the setup wizard: whether there is one, and the most important settings of the watch page. */
@HiltViewModel
class SetupWatchViewModel @Inject constructor(
    connection: WatchConnection,
    private val watchSettings: WatchSettingsRepository,
) : ViewModel() {

    /** `null` as long as it isn't known yet whether there is a watch. */
    val watch: StateFlow<WatchSetup?> = connection.setup
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), connection.lastSetup)

    val settings: StateFlow<WatchSettings> = watchSettings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchSettings())

    fun setQuality(quality: WearQuality) {
        viewModelScope.launch { watchSettings.setQuality(quality) }
    }

    fun setRequireCharging(requireCharging: Boolean) {
        viewModelScope.launch { watchSettings.setRequireCharging(requireCharging) }
    }
}
