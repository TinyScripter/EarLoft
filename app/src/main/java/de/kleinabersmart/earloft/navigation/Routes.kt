package de.kleinabersmart.earloft.navigation

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

/** Ein Genre; [name] ist eine seiner Schreibweisen (Groß-/Kleinschreibung zählt nicht). */
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

/** Detailseite eines abonnierten Podcasts. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Info-Seite eines Podcasts (Beschreibung, Feed-Adresse). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastInfoRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/**
 * Suche im aktuellen Bereich (Musik, Podcasts oder Bücher), geöffnet über die Lupe im Kopf von Start und Bibliothek;
 * liegt im Stack des Tabs, aus dem sie kam.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SearchRoute(
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Suche in den Folgen eines Podcasts (Überschrift und Inhaltsangabe). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastSearchRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Einstellungen eines Podcasts. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastSettingsRoute(
    val feedId: String,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Seite „Podcast hinzufügen“: Feed-Adresse, OPML-Import und Suche in Verzeichnissen (Konzept 12.10). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AddPodcastRoute(
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

/** Vorschau eines Verzeichnistreffers; Titel, Autor und Bild stammen aus dem Verzeichnis, bis der Feed geladen ist. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PodcastPreviewRoute(
    val feedUrl: String,
    val title: String,
    val author: String,
    val imageUrl: String?,
    @EncodeDefault val instanceId: String = newInstanceId(),
) : NavKey

enum class BookGroupKind { SERIES, AUTHOR, NARRATOR }

/** Alle Bücher einer Serie, eines Autors oder eines Sprechers. */
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

/** Die Musik-Warteschlange; [overPlayer]: über der Wiedergabe-Ansicht geöffnet (Vollbild), sonst von Start aus. */
@Serializable
data class QueueRoute(val overPlayer: Boolean) : NavKey

/** Die Warteschlange der Podcasts (Zeile „Weiterhören“) bearbeiten. */
@Serializable
data object PodcastQueueRoute : NavKey

/** Suche nur in der Warteschlange der Podcasts. */
@Serializable
data object PodcastQueueSearchRoute : NavKey

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

/** Seiten des Einrichtungsassistenten (Konzept 15) in ihrer Reihenfolge. */
enum class SetupPage { WELCOME, AREAS, MUSIC, PODCASTS, BOOKS, NOTIFICATIONS, WATCH, DESIGN, DONE }

/**
 * Eine Seite des Einrichtungsassistenten. Jede Seite liegt als eigener Eintrag im Stack: „Zurück“ und die Zurück-Geste
 * führen so zur vorigen Seite, und Formulare (Server, Abo) öffnen sich darüber und kehren hierher zurück.
 */
@Serializable
data class SetupRoute(val page: SetupPage) : NavKey

/** Bildschirme, auf denen Bottom-Bar und Mini-Player ausgeblendet werden. */
val NavKey.isFullScreenPlayer: Boolean
    get() = this == NowPlayingRoute || (this is QueueRoute && overPlayer)

private fun newInstanceId(): String = UUID.randomUUID().toString()
