package keinabersmart.kleinaberlaut.ui.snackbar

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
 * Ein Hinweis an den Nutzer. Mit [actionLabel] und [onAction] trägt die Snackbar eine Aktion (zum Beispiel „Rückgängig“);
 * [onAction] läuft auf dem Main-Thread, wenn die Aktion getippt wurde. [keep]: Ein neuer Hinweis ersetzt diesen nicht, sondern
 * wartet, bis er abgelaufen ist (für Hinweise, die der Nutzer nicht verpassen soll).
 */
data class SnackbarMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val keep: Boolean = false,
)

/**
 * Der eine Weg für Hinweise in der ganzen App (statt Toast): Oberfläche wie Hintergrunddienste rufen [show], die Wurzel der
 * Oberfläche zeigt sie in ihrer Snackbar. Ein neuer Hinweis ersetzt den gerade sichtbaren (außer [SnackbarMessage.keep]); ist die
 * App nicht sichtbar, geht er verloren.
 */
@Singleton
class Snackbars @Inject constructor() {
    private val _messages = MutableSharedFlow<SnackbarMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<SnackbarMessage> = _messages.asSharedFlow()

    fun show(message: SnackbarMessage) {
        _messages.tryEmit(message)
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

/** Aus der Oberfläche heraus: `LocalSnackbars.current.show("…")`. Ohne Wurzel (Vorschau) geht der Hinweis ins Leere. */
val LocalSnackbars = staticCompositionLocalOf { Snackbars() }
