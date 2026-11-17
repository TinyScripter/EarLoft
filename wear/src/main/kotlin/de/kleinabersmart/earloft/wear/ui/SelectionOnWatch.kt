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
 * Knopf „Download“ einer Auswahl des Handys ([kind] mit [refId]: Album, Playlist, Favoriten; Wear-Konzept 17): Stand aus der
 * Auswahl der Uhr und Tipp, der die Auswahl am Handy setzt. Gehört einem ViewModel ([scope]).
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
     * Gewünschter Stand nach einem Tipp, bis das Manifest des Handys ihn bestätigt (oder [PENDING_TIMEOUT_MS] vergeht):
     * Der Knopf spränge sonst bis zum nächsten Manifest zurück.
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

    /** Holt die Auswahl auf die Uhr bzw. nimmt sie herunter; das Handy wählt aus und schickt ein neues Manifest. */
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
