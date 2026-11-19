package de.kleinabersmart.earloft.wear.ui

import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.sync.PhoneSelections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "Download" button of a phone selection ([kind] with [refId]: album, playlist, favorites; Wear concept 17): state from the
 * watch's selection and a tap that sets the selection on the phone. Belongs to a ViewModel ([scope]).
 */
internal class SelectionOnWatch(
    private val kind: WearSelectionKind,
    private val refId: Long?,
    storage: WatchStorageRepository,
    private val phoneSelections: PhoneSelections,
    private val scope: CoroutineScope,
) {
    private val key = kind.key(refId)

    /**
     * Desired state after a tap until the phone's manifest confirms it (or [PENDING_TIMEOUT_MS] passes):
     * otherwise the button would jump back until the next manifest.
     */
    private val pending = MutableStateFlow<Boolean?>(null)
    private val failed = MutableStateFlow(false)
    private var pendingTimeout: Job? = null

    val state: StateFlow<OnWatchState?> =
        combine(storage.selectedKeys(), storage.selections(), pending, failed) { keys, selections, wanted, failedNow ->
            val confirmed = key in keys
            if (wanted == confirmed) pending.value = null
            val item = selections.firstOrNull { it.key == key }
            onWatchState(confirmed, wanted, item?.progress, failedNow, downloadFailed = (item?.failedCount ?: 0) > 0)
        }.orOnError(null).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Brings the selection to the watch or removes it; the phone selects and sends a new manifest. */
    fun set(selected: Boolean) {
        scope.launch {
            failed.value = false
            pending.value = selected
            if (!phoneSelections.setSelected(kind, refId, selected)) {
                pending.value = null
                failed.value = true
                return@launch
            }
            pendingTimeout?.cancel()
            pendingTimeout = launch {
                delay(PENDING_TIMEOUT_MS)
                pending.value = null
            }
        }
    }

    private companion object {
        const val PENDING_TIMEOUT_MS = 20_000L
    }
}
