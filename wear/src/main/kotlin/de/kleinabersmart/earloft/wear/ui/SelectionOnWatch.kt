package de.kleinabersmart.earloft.wear.ui

import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.sync.PhoneSelections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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

    private val toggle = PendingToggle(scope)

    val state: StateFlow<OnWatchState?> =
        combine(storage.selectedKeys(), storage.selections(), toggle.pending, toggle.failed) { keys, selections, wanted, failedNow ->
            val confirmed = key in keys
            toggle.confirmed(confirmed)
            val item = selections.firstOrNull { it.key == key }
            onWatchState(confirmed, wanted, item?.progress, failedNow, downloadFailed = (item?.failedCount ?: 0) > 0)
        }.orOnError(null).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Brings the selection to the watch or removes it; the phone selects and sends a new manifest. */
    fun set(selected: Boolean) = toggle.request(selected) { phoneSelections.setSelected(kind, refId, it) }
}
