package de.kleinabersmart.earloft.ui.spoken

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.DatabaseExportResult
import de.kleinabersmart.earloft.core.data.podcast.PodcastSourceSwitch
import de.kleinabersmart.earloft.core.data.podcast.SourceCheck
import de.kleinabersmart.earloft.ui.settings.DatabaseExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Flow of "Switch source" (concept 12.10). */
sealed interface SourceSwitchState {
    data object Idle : SourceSwitchState
    data object Checking : SourceSwitchState
    data class Failed(@param:StringRes val message: Int, val detail: String? = null) : SourceSwitchState

    /** Checked; waiting for confirmation. */
    data class Ready(val episodes: Int, val matched: Int, val keptUnmatched: Int) : SourceSwitchState
    data object Switching : SourceSwitchState
    data object Done : SourceSwitchState
}

/** Result of the database export in the dialog; `null` = failed. */
data class SwitchExport(val result: DatabaseExportResult?)

@HiltViewModel(assistedFactory = SourceSwitchViewModel.Factory::class)
class SourceSwitchViewModel @AssistedInject constructor(
    @Assisted private val feedId: String,
    private val sourceSwitch: PodcastSourceSwitch,
    private val databaseExport: DatabaseExport,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<SourceSwitchState>(SourceSwitchState.Idle)
    val state: StateFlow<SourceSwitchState> = _state

    private val _export = MutableStateFlow<SwitchExport?>(null)
    val export: StateFlow<SwitchExport?> = _export

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    private var checked: SourceCheck.Ready? = null

    /** Loads the new source and matches the episodes; nothing is changed yet. */
    fun check(input: String) {
        _state.value = SourceSwitchState.Checking
        viewModelScope.launch {
            _state.value = when (val result = sourceSwitch.check(feedId, input.trim())) {
                is SourceCheck.Ready -> {
                    checked = result
                    SourceSwitchState.Ready(result.episodes, result.matched, result.keptUnmatched)
                }
                SourceCheck.SameSource -> SourceSwitchState.Failed(R.string.podcast_source_switch_same)
                is SourceCheck.Taken -> SourceSwitchState.Failed(R.string.podcast_source_switch_taken, result.title)
                is SourceCheck.Failed -> SourceSwitchState.Failed(result.result.message)
            }
        }
    }

    /**
     * When the dialog closes: the ViewModel belongs to the page and outlives it; without a reset the
     * next dialog would close again immediately after a switch ("Done"), and old errors and export results would be in it.
     */
    fun reset() {
        checked = null
        _state.value = SourceSwitchState.Idle
        _export.value = null
    }

    /** Back to input. */
    fun back() {
        checked = null
        _state.value = SourceSwitchState.Idle
    }

    /** Performs the checked switch; in the app scope so it doesn't stop halfway when the page goes away. */
    fun switch() {
        val ready = checked ?: return
        _state.value = SourceSwitchState.Switching
        viewModelScope.launch {
            val switched = applicationScope.async { sourceSwitch.switch(ready) }.await()
            checked = null
            _state.value = if (switched) SourceSwitchState.Done else SourceSwitchState.Failed(R.string.podcast_source_switch_changed)
        }
    }

    /** Backup before the switch: the same file as Settings → Podcasts → "Export database". */
    fun exportDatabase(uri: Uri) {
        _exporting.value = true
        viewModelScope.launch {
            _export.value = SwitchExport(databaseExport.export(uri))
            _exporting.value = false
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(feedId: String): SourceSwitchViewModel
    }
}
