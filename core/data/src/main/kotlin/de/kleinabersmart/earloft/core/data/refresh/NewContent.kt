package de.kleinabersmart.earloft.core.data.refresh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * New episodes of a podcast ([episodeIds] are `books` IDs); [notify] says whether the user wants to be notified about them,
 * [autoDownload] whether the automation downloads them anyway.
 */
data class NewEpisodes(
    val feedId: String,
    val feedTitle: String,
    val episodeIds: List<Long>,
    val notify: Boolean = true,
    val autoDownload: Boolean = false,
) {
    val count: Int get() = episodeIds.size
}

/** An album with [songs] new tracks. */
data class NewAlbum(val albumId: Long, val title: String, val artist: String, val songs: Int)

/** A subscribed show from ARD Sounds. */
data class NewShow(val id: String, val title: String)

/** A new book; [ardShow] if it belongs to a subscribed show from ARD Sounds. */
data class NewBook(val bookId: Long, val title: String, val author: String, val ardShow: NewShow? = null)

/** What a background sync brought new in a section; each section has its own notification. */
sealed interface NewContent {
    val isEmpty: Boolean

    data class Music(val albums: List<NewAlbum>) : NewContent {
        override val isEmpty: Boolean get() = albums.isEmpty()
    }

    /** [moved]: titles of the subscriptions that moved to a new address (concept 12.10 "Feed move"). */
    data class Podcasts(val episodes: List<NewEpisodes>, val moved: List<String> = emptyList()) : NewContent {
        override val isEmpty: Boolean get() = episodes.isEmpty() && moved.isEmpty()
    }

    data class Books(val books: List<NewBook>) : NewContent {
        override val isEmpty: Boolean get() = books.isEmpty()
    }
}

/** Shows [NewContent] as a notification; the app provides the implementation (texts, channel, cover, tap target). */
interface NewContentNotifier {
    suspend fun show(content: NewContent)
}

enum class RefreshKind { MUSIC, PODCASTS, BOOKS }

/** Feedback on a sync, shown by the UI as a hint while the app is visible. */
sealed interface RefreshEvent {
    val kind: RefreshKind

    data class Started(override val kind: RefreshKind) : RefreshEvent

    /** [newCount] new episodes or books; [failed] if at least one source was unreachable. */
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
