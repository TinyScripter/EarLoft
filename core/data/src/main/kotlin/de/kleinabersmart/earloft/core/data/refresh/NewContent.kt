package de.kleinabersmart.earloft.core.data.refresh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Neue Folgen eines Podcasts ([episodeIds] sind `books`-Ids); [notify] sagt, ob der Nutzer dafür benachrichtigt werden will. */
data class NewEpisodes(val feedId: String, val feedTitle: String, val episodeIds: List<Long>, val notify: Boolean = true) {
    val count: Int get() = episodeIds.size
}

/** Ein Album mit [songs] neuen Titeln. */
data class NewAlbum(val albumId: Long, val title: String, val artist: String, val songs: Int)

/** Ein neues Buch. */
data class NewBook(val bookId: Long, val title: String, val author: String)

/** Was ein Abgleich im Hintergrund in einem Bereich Neues gebracht hat; jeder Bereich hat seine eigene Benachrichtigung. */
sealed interface NewContent {
    val isEmpty: Boolean

    data class Music(val albums: List<NewAlbum>) : NewContent {
        override val isEmpty: Boolean get() = albums.isEmpty()
    }

    data class Podcasts(val episodes: List<NewEpisodes>) : NewContent {
        override val isEmpty: Boolean get() = episodes.isEmpty()
    }

    data class Books(val books: List<NewBook>) : NewContent {
        override val isEmpty: Boolean get() = books.isEmpty()
    }
}

/** Zeigt [NewContent] als Benachrichtigung; die App liefert die Umsetzung (Texte, Kanal, Cover, Ziel des Tipps). */
interface NewContentNotifier {
    suspend fun show(content: NewContent)
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
