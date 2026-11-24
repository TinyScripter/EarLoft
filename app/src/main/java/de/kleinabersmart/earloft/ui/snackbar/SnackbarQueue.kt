package de.kleinabersmart.earloft.ui.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import kotlinx.coroutines.channels.Channel

/**
 * Decides which [SnackbarMessage] the app's snackbar shows next; only on the main thread ([offer] and [run]).
 *
 * - A new message replaces a visible one that isn't [SnackbarMessage.keep] (e.g. "Refreshing" by its result).
 * - A kept one stays, and new messages wait behind it – except one with an action of its own that isn't kept ("Undo"):
 *   it refers to what the user just did and would be pointless after a hint that stays until dismissed. It goes first,
 *   and the kept one comes back after it (review3 Q3).
 * - A kept message that is already visible or waiting isn't queued a second time (e.g. "New at the start" after every
 *   emission of a refresh, review3 Q5).
 */
internal class SnackbarQueue(private val host: SnackbarHostState) {
    private val waiting = ArrayDeque<SnackbarMessage>()
    private var visible: SnackbarMessage? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The messages still to come, in order (for tests). */
    val pending: List<SnackbarMessage> get() = waiting.toList()

    fun offer(message: SnackbarMessage) {
        if (message.keep && (visible.sameAs(message) || waiting.any { it.sameAs(message) })) return
        val shown = visible
        when {
            shown == null -> waiting.addLast(message)
            !shown.keep -> {
                if (message.isUrgent) waiting.addFirst(message) else waiting.addLast(message)
                host.currentSnackbarData?.dismiss()
            }
            message.isUrgent -> {
                waiting.addFirst(shown)
                waiting.addFirst(message)
                host.currentSnackbarData?.dismiss()
            }
            else -> waiting.addLast(message)
        }
        wake.trySend(Unit)
    }

    /** Shows the messages one after another; runs as long as the UI root does. */
    suspend fun run(): Nothing {
        while (true) {
            val next = waiting.removeFirstOrNull()
            if (next == null) {
                wake.receive()
                continue
            }
            visible = next
            val result = try {
                host.showSnackbar(AppSnackbarVisuals(next))
            } finally {
                visible = null
            }
            if (result == SnackbarResult.ActionPerformed) next.onAction?.invoke()
        }
    }

    private val SnackbarMessage.isUrgent get() = actionLabel != null && !keep

    private fun SnackbarMessage?.sameAs(other: SnackbarMessage) = this != null && text == other.text && actionLabel == other.actionLabel
}

/** The snackbar visuals of a [SnackbarMessage]. */
private class AppSnackbarVisuals(message: SnackbarMessage) : SnackbarVisuals {
    override val message: String = message.text
    override val actionLabel: String? = message.actionLabel
    /** A hint without a timeout can always be dismissed even without its action. */
    override val withDismissAction: Boolean = message.duration == SnackbarDuration.Indefinite
    override val duration: SnackbarDuration = message.duration
}
