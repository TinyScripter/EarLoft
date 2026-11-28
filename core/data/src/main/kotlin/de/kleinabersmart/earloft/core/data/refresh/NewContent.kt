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

/**
 * What was just found together with [unread], what the section's notification still shows (neither tapped nor swiped away):
 * otherwise a later sync replaced it, and an earlier podcast with its buttons disappeared without being seen (review5 P19).
 * The newer entries come first; an album, podcast or book in both counts once, with the newer title.
 *
 * At most [MAX_MERGED] albums, podcasts or books and [MAX_MERGED_EPISODES] episodes per podcast are kept: a notification left
 * standing for months would otherwise carry its whole history in its extras, up to the binder's limit, beyond which it is dropped.
 */
fun NewContent.withUnread(unread: NewContent?): NewContent = when {
    this is NewContent.Music && unread is NewContent.Music -> NewContent.Music(
        (albums + unread.albums).groupBy { it.albumId }.values
            .map { same -> same.first().copy(songs = same.sumOf { it.songs }) }
            .sortedByDescending { it.songs }
            .take(MAX_MERGED),
    )
    this is NewContent.Podcasts && unread is NewContent.Podcasts -> NewContent.Podcasts(
        episodes = (episodes + unread.episodes).groupBy { it.feedId }.values
            .map { same -> same.first().copy(episodeIds = same.flatMap { it.episodeIds }.distinct().take(MAX_MERGED_EPISODES)) }
            .take(MAX_MERGED),
        moved = (moved + unread.moved).distinct().take(MAX_MERGED),
    )
    this is NewContent.Books && unread is NewContent.Books -> NewContent.Books((books + unread.books).distinctBy { it.bookId }.take(MAX_MERGED))
    else -> this
}

internal const val MAX_MERGED = 100
internal const val MAX_MERGED_EPISODES = 50

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
