package keinabersmart.kleinaberlaut.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.UUID

/*
 * Detail-Routen tragen eine Instanz-ID: Dasselbe Album kann gleichzeitig im Start- und im
 * aktuellen Tab-Stack liegen (oder zweimal in einem Stack), braucht aber je eigenen UI-Zustand
 * und eigenes ViewModel.
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

/** Detailseite eines abonnierten Podcasts. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

enum class BookGroupKind { SERIES, AUTHOR }

/** Alle Bücher einer Serie oder eines Autors. */
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
data object FavoritesRoute : NavKey

@Serializable
data object NowPlayingRoute : NavKey

@Serializable
data object QueueRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object AddServerRoute : NavKey

@Serializable
data class EditServerRoute(val sourceId: String) : NavKey

@Serializable
data object WatchSettingsRoute : NavKey

@Serializable
data object AboutRoute : NavKey

@Serializable
data object LicensesRoute : NavKey

/** Bildschirme, auf denen Bottom-Bar und Mini-Player ausgeblendet werden. */
val NavKey.isFullScreenPlayer: Boolean
    get() = this == NowPlayingRoute || this == QueueRoute

private fun newInstanceId(): String = UUID.randomUUID().toString()
