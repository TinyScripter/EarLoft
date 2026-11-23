package de.kleinabersmart.earloft.wear.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A switch whose state the phone confirms (what goes onto the watch): after a tap the desired state shows ([pending]) until the
 * phone's manifest confirms it ([confirmed]) or [TIMEOUT_MS] passes, otherwise the button would jump back until the next
 * manifest; a send that failed shows [failed]. Shared by selections and books (review2 J96).
 */
internal class PendingToggle(private val scope: CoroutineScope) {
    private val _pending = MutableStateFlow<Boolean?>(null)
    val pending: StateFlow<Boolean?> = _pending.asStateFlow()

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var timeout: Job? = null

    /** The phone reports [state]: the desired state has arrived once both agree. */
    fun confirmed(state: Boolean) {
        if (_pending.value == state) _pending.value = null
    }

    /** Shows [selected] at once and asks the phone via [send]; `false` from it counts as failed. */
    fun request(selected: Boolean, send: suspend (Boolean) -> Boolean) {
        scope.launch {
            _failed.value = false
            _pending.value = selected
            if (!send(selected)) {
                _pending.value = null
                _failed.value = true
                return@launch
            }
            timeout?.cancel()
            timeout = launch {
                delay(TIMEOUT_MS)
                _pending.value = null
            }
        }
    }

    private companion object {
        /** Generous: the phone sends the episode manifest only after 2 s of quiet, and the watch creates the entries after that. */
        const val TIMEOUT_MS = 20_000L
    }
}
