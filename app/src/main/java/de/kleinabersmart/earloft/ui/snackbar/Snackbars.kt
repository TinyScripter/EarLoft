package de.kleinabersmart.earloft.ui.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A message to the user. With [actionLabel] and [onAction] the snackbar carries an action (for example "Undo");
 * [onAction] runs on the main thread when the action was tapped. [keep]: a new message doesn't replace this one but
 * waits until it has expired or was dismissed (for messages the user shouldn't miss); only one with an action of its own
 * ("Undo") goes first, and this one comes back after it ([SnackbarQueue]).
 */
data class SnackbarMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val keep: Boolean = false,
)

/**
 * The single path for messages across the whole app (instead of Toast): UI and background services call [show], the UI
 * root shows them in its snackbar, in the order [SnackbarQueue] decides. A new message replaces the currently visible one
 * (except [SnackbarMessage.keep]); if the app isn't visible, it is lost.
 */
@Singleton
class Snackbars @Inject constructor() {
    private val _messages = MutableSharedFlow<SnackbarMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<SnackbarMessage> = _messages.asSharedFlow()

    private val _withdrawn = MutableSharedFlow<SnackbarMessage>(extraBufferCapacity = 8)

    /** Messages taken back ([withdraw]). */
    val withdrawn: SharedFlow<SnackbarMessage> = _withdrawn.asSharedFlow()

    fun show(message: SnackbarMessage) {
        _messages.tryEmit(message)
    }

    /** Takes back exactly this [message] (the same instance): it no longer shows, or disappears if it is visible. */
    fun withdraw(message: SnackbarMessage) {
        _withdrawn.tryEmit(message)
    }

    fun show(
        text: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) = show(SnackbarMessage(text, actionLabel, onAction, duration))
}

@HiltViewModel
class SnackbarViewModel @Inject constructor(val snackbars: Snackbars) : ViewModel()

/** From within the UI: `LocalSnackbars.current.show("…")`. Without a root (preview) the message goes nowhere. */
val LocalSnackbars = staticCompositionLocalOf { Snackbars() }
