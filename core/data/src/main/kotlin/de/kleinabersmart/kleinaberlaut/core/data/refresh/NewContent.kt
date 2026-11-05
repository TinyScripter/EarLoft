package de.kleinabersmart.kleinaberlaut.core.data.refresh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Neue Folgen eines Podcasts; [notify] sagt, ob der Nutzer dafür benachrichtigt werden will. */
data class NewEpisodes(val feedId: String, val feedTitle: String, val count: Int, val notify: Boolean = true)

/** Was ein Abgleich im Hintergrund Neues gebracht hat. */
data class NewContent(val episodes: List<NewEpisodes> = emptyList(), val books: Int = 0) {
    val isEmpty: Boolean get() = episodes.isEmpty() && books == 0
}

/** Zeigt [NewContent] als Benachrichtigung; die App liefert die Umsetzung (Texte, Kanal, Ziel des Tipps). */
interface NewContentNotifier {
    fun show(content: NewContent)
}

enum class RefreshKind { MUSIC, PODCASTS, BOOKS }

/** Rückmeldung zu einem Abgleich, den die Oberfläche als Hinweis zeigt, solange die App sichtbar ist. */
sealed interface RefreshEvent {
    val kind: RefreshKind

    data class Started(override val kind: RefreshKind) : RefreshEvent

    /** [newCount] neue Folgen bzw. Bücher; [failed], wenn mindestens eine Quelle nicht erreichbar war. */
    data class Finished(override val kind: RefreshKind, val newCount: Int, val failed: Boolean) : RefreshEvent
}

@Singleton
class RefreshEvents @Inject constructor() {
    private val _events = MutableSharedFlow<RefreshEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<RefreshEvent> = _events.asSharedFlow()

    fun emit(event: RefreshEvent) {
        _events.tryEmit(event)
    }
}
