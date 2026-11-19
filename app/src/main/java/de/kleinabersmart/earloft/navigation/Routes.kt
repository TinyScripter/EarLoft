package de.kleinabersmart.earloft.navigation

import androidx.navigation3.runtime.NavKey
import de.kleinabersmart.earloft.core.model.BookKind
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.UUID

/*
 * Detail routes carry an instance ID: the same album can be in the Home stack and the
 * current tab stack at the same time (or twice in one stack), but needs its own UI state
 * and its own ViewModel for each.
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AlbumRoute(
    val albumId: Long,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ArtistRoute(
    val artistId: Long,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** A genre; [name] is one of its spellings (case doesn't matter). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GenreRoute(
    val name: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PlaylistRoute(
    val playlistId: Long,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BookRoute(
    val bookId: Long,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Detail page of a subscribed podcast. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Info page of a podcast (description, feed address). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastInfoRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/**
 * Search in the current area (music, podcasts or books), opened via the magnifier in the header of Home and Library;
 * lives in the stack of the tab it came from.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SearchRoute(
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Search within a podcast's episodes (title and summary). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastSearchRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Settings of a podcast. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastSettingsRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** "Add podcast" page: feed address, OPML import and directory search (concept 12.10). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AddPodcastRoute(
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Preview of a directory hit; title, author and image come from the directory until the feed is loaded. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastPreviewRoute(
    val feedUrl: String,
    val title: String,
    val author: String,
    val imageUrl: String?,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** "ARD Sounds" page in *Books*: audiobook/radio play categories and search (`books_improvements.md` M9). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ArdSoundsRoute(
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** The books of an ARD Sounds show. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ArdShowRoute(
    val showId: String,
    val title: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Preview of a book from ARD Sounds; [key] is its ID there. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ArdBookRoute(
    val key: String,
    val title: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

enum class BookGroupKind { SERIES, AUTHOR, NARRATOR, GENRE }

/** All books of a series, author, narrator or genre. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BookGroupRoute(
    val kind: BookGroupKind,
    val name: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

@Serializable
data object AddAudiobookshelfRoute : NavKey

@Serializable
data class EditAudiobookshelfRoute(val serverId: String) : NavKey

@Serializable
data object FavoritesRoute : NavKey

@Serializable
data object NowPlayingRoute : NavKey

/** The music queue; [overPlayer]: opened over the playback screen (full screen), otherwise from Home. */
@Serializable
data class QueueRoute(val overPlayer: Boolean) : NavKey

/** Edit the podcast queue ("Continue listening" row). */
@Serializable
data object PodcastQueueRoute : NavKey

/** Edit the book queue ("Continue listening" row). */
@Serializable
data object BookQueueRoute : NavKey

/** Search only within the podcast queue. */
@Serializable
data object PodcastQueueSearchRoute : NavKey

/** History of played episodes (from the podcasts' "Statistics" tab). */
@Serializable
data object PodcastHistoryRoute : NavKey

/** Collected bookmarks of books and episodes (from the library header). */
@Serializable
data class BookmarksRoute(val kind: BookKind) : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object AddServerRoute : NavKey

@Serializable
data class EditServerRoute(val sourceId: String) : NavKey

@Serializable
data object SettingsGeneralRoute : NavKey

@Serializable
data object SettingsMusicRoute : NavKey

@Serializable
data object SettingsPodcastsRoute : NavKey

@Serializable
data object SettingsAudiobooksRoute : NavKey

@Serializable
data object WatchSettingsRoute : NavKey

@Serializable
data object AboutRoute : NavKey

@Serializable
data object LicensesRoute : NavKey

@Serializable
data object PrivacyRoute : NavKey

/** Pages of the setup wizard (concept 15) in their order. */
enum class SetupPage { WELCOME, AREAS, MUSIC, PODCASTS, BOOKS, NOTIFICATIONS, WATCH, DESIGN, DONE }

/**
 * A page of the setup wizard. Each page is a separate entry in the stack: "Back" and the back gesture
 * thus lead to the previous page, and forms (server, subscription) open on top and return here.
 */
@Serializable
data class SetupRoute(val page: SetupPage) : NavKey

/** Screens on which the bottom bar and mini player are hidden. */
val NavKey.isFullScreenPlayer: Boolean
    get() = this == NowPlayingRoute || (this is QueueRoute && overPlayer)

private fun newInstanceId(): String = UUID.randomUUID().toString()
