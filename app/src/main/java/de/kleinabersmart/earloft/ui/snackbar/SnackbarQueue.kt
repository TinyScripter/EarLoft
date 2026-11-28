package de.kleinabersmart.earloft.ui.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import kotlinx.coroutines.CancellationException
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

    /** [visible] was withdrawn: it must not come back when its root goes away right after. */
    private var visibleWithdrawn = false

    /** [visible] is a kept message temporarily paused for an urgent one; it will be shown again. */
    private var visibleInterrupted = false
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
                visibleInterrupted = true
                host.currentSnackbarData?.dismiss()
            }
            else -> waiting.addLast(message)
        }
        wake.trySend(Unit)
    }

    /** Exactly this [message] (the same instance) no longer shows; if it is visible, it disappears. */
    fun withdraw(message: SnackbarMessage) {
        waiting.removeAll { it === message }
        if (visible === message) {
            visibleWithdrawn = true
            host.currentSnackbarData?.dismiss()
        }
    }

    /** Shows the messages one after another; runs as long as the UI root does. A new root takes over with the same queue. */
    suspend fun run(): Nothing {
        while (true) {
            val next = waiting.removeFirstOrNull()
            if (next == null) {
                wake.receive()
                continue
            }
            visible = next
            visibleWithdrawn = false
            visibleInterrupted = false
            val result = try {
                host.showSnackbar(AppSnackbarVisuals(next))
            } catch (e: CancellationException) {
                // The UI root went away while the message was visible (rotation, switch of theme or language): the next root shows
                // it again; dismissed, expired and withdrawn messages return normally (review T31). Not one its list withdrew while
                // going away with the same root ("New at the start"): "Show" would do nothing (review4 8.6 Y5).
                if (!visibleWithdrawn) waiting.addFirst(next)
                throw e
            } finally {
                visible = null
            }
            when (result) {
                SnackbarResult.ActionPerformed -> next.onAction?.invoke()
                SnackbarResult.Dismissed -> if (!visibleWithdrawn && !visibleInterrupted) next.onDismissed?.invoke()
            }
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
