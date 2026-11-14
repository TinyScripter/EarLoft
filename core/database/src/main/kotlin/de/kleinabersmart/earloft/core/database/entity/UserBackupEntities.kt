package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind

/*
 * Zeilen der gesicherten Datenbank `user.db` (Review K9): nur das, was keine Quelle wiederherstellt, mit Schlüsseln, die
 * auf einem neuen Gerät noch passen. Abgleichsstände (Generationen, Zeitpunkte, ETags) fehlen absichtlich; sie gehören
 * zum Gerät und beginnen nach dem Wiederherstellen von vorn.
 */

/** Ein Musik-Server (`sources`, nur Subsonic). Das Passwort liegt im SecretStore und kommt nicht mit. */
@Entity(tableName = "servers")
data class UserServerEntity(
    @PrimaryKey val sourceId: String,
    val type: SourceType,
    val name: String,
    val baseUrl: String?,
    val username: String?,
    val priority: Int,
    val serverCapabilities: String?,
    val enabled: Boolean,
)

/** Ein Audiobookshelf-Server (`spoken_servers`). API-Key bzw. Tokens kommen nicht mit, nur ob per OpenID angemeldet war. */
@Entity(tableName = "spoken_servers")
data class UserSpokenServerEntity(
    @PrimaryKey val serverId: String,
    val name: String,
    val baseUrl: String,
    val libraryId: String?,
    val enabled: Boolean,
    @ColumnInfo(defaultValue = "0") val openId: Boolean = false,
    /** Abgewählte Buch-Bibliotheken (v17), wie in der Hauptdatenbank. */
    @ColumnInfo(defaultValue = "''") val hiddenLibraries: String = "",
)

/**
 * Ein abonnierter (auch archivierter) Podcast (`podcast_feeds`) samt seinen Einstellungen; Podcasts vom Gerät baut der
 * Scan neu auf.
 */
@Entity(tableName = "podcasts")
data class UserPodcastEntity(
    @PrimaryKey val feedId: String,
    val url: String,
    val title: String,
    val author: String,
    val imageUrl: String?,
    val autoDownload: Boolean,
    val newestFirst: Boolean,
    val continuous: Boolean,
    val notifyNew: Boolean,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** Tempo und Effekte des Abos (seit Version 11), `null` = Vorgabe des Bereichs. */
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
    @ColumnInfo(defaultValue = "0") val skipIntroS: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipOutroS: Int = 0,
    /** Folgenliste und „Weiterhören“ (seit Version 13). */
    @ColumnInfo(defaultValue = "'ALL'") val episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
    @ColumnInfo(defaultValue = "0") val listOldestFirst: Boolean = false,
    @ColumnInfo(defaultValue = "'ALL_NEW'") val queueMode: FeedQueueMode = FeedQueueMode.ALL_NEW,
    @ColumnInfo(defaultValue = "0") val keepEpisodes: Int = 0,
    /** Bereithalten je Abo (seit Version 15), `null` = Vorgabe. */
    val autoDownloadCount: Int? = null,
)

/** Gehörte Zeit je Eintrag (`listening_stats`), über den natürlichen Schlüssel statt der laufenden `statId`. */
@Entity(tableName = "listening_stats", primaryKeys = ["source", "groupKey", "itemKey"])
data class UserListeningStatEntity(
    val source: String,
    val groupKey: String,
    val itemKey: String,
    val kind: BookKind,
    val groupTitle: String,
    val itemTitle: String,
    val listenedMs: Long,
    val lastListenedAt: Long,
)

/** Gehörte Zeit je Tag und Gruppe (`listening_days`). */
@Entity(tableName = "listening_days", primaryKeys = ["day", "kind", "groupKey"])
data class UserListeningDayEntity(
    val day: Long,
    val kind: BookKind,
    val groupKey: String,
    val listenedMs: Long,
    /** Durch „Stille kürzen“ gespart (seit Version 12). */
    @ColumnInfo(defaultValue = "0") val savedMs: Long = 0,
)

/**
 * Fortschritt eines Buchs oder einer Folge (`book_progress`) über `(serverId, remoteId)` des Buchs statt der laufenden
 * `bookId`: Feed und guid, Audiobookshelf-Server und Item, bzw. „local“ und der Pfad im Ordner. Von Audiobookshelf-Büchern
 * nur die Stände, die der Server noch nicht kennt (`dirty`); den Rest holt der Abgleich zurück.
 */
@Entity(tableName = "book_progress", primaryKeys = ["serverId", "remoteId"])
data class UserBookProgressEntity(
    val serverId: String,
    val remoteId: String,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    /** Eigenes Tempo; `null` = das des Abos bzw. Bereichs (nullbar seit Version 11). */
    val speed: Float?,
    val updatedAt: Long,
    val dirty: Boolean,
    /** Aus „Weiterhören“ genommen (seit Version 10). */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** Eigene Effekte (seit Version 11). */
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
)

/**
 * Ein Lesezeichen (`bookmarks`, seit Version 14) über `(serverId, remoteId)` seines Buchs und seine Position: Audiobookshelf
 * kennt Lesezeichen an ihrer Zeit, zwei an derselben Stelle ergeben keinen Sinn. Gelöschte fehlen. Von Audiobookshelf-Büchern
 * nur die, die der Server noch nicht hat (`remoteTime` fehlt oder geändert); den Rest holt der Abgleich zurück.
 */
@Entity(tableName = "bookmarks", primaryKeys = ["serverId", "remoteId", "positionMs"])
data class UserBookmarkEntity(
    val serverId: String,
    val remoteId: String,
    val positionMs: Long,
    val title: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Zeit beim Server (Sekunden), `null` = dort noch unbekannt. */
    val remoteTime: Double?,
)

/**
 * Was mit dem Download einer Podcast-Folge war (`book_tracks.downloadState`), über Feed und guid. [wanted]: geladen oder
 * wartend – kommt auf einem neuen Gerät wieder, solange die Folge nicht gehört ist. Sonst entfernt oder fehlgeschlagen –
 * die Automatik lädt sie dann auch dort nicht.
 */
@Entity(tableName = "episode_downloads", primaryKeys = ["serverId", "remoteId"])
data class UserEpisodeDownloadEntity(
    val serverId: String,
    val remoteId: String,
    val wanted: Boolean,
)

/** Eine gemerkte Podcast-Folge (`books.starredAt`, seit Version 16), über Feed und guid. */
@Entity(tableName = "episode_stars", primaryKeys = ["serverId", "remoteId"])
data class UserEpisodeStarEntity(
    val serverId: String,
    val remoteId: String,
    val starredAt: Long,
)

/**
 * Ein Buch aus ARD Sounds in der Bibliothek (`books` mit `serverId = ardsounds`, seit Version 18), über die Kennung bei ARD Sounds.
 * Das Buch selbst holt die App auf einem neuen Gerät wieder von dort; Fortschritt und Lesezeichen kommen über ihre eigenen Zeilen.
 * [parts] (seit Version 19) sind seine Teile in ihrer Reihenfolge, auch die, die ARD Sounds nicht mehr führt: Ohne sie verschöben
 * sich Position und Lesezeichen auf dem neuen Gerät, sobald ein Teil abgelaufen ist (Format: `ArdBookParts.kt` in `:core:data`).
 */
@Entity(tableName = "ard_books")
data class UserArdBookEntity(
    @PrimaryKey val remoteId: String,
    val addedAt: Long,
    val parts: String? = null,
)

/**
 * Ein Song über seine Metadaten statt der `songId` (siehe `KeyedSong`): Album-Künstler, Album, CD, Titelnummer und Titel,
 * normalisiert wie beim Zusammenführen.
 */
data class UserSongKey(
    val albumArtist: String,
    val album: String,
    val disc: Int,
    val track: Int,
    val title: String,
)

/** Eine Playlist nur auf diesem Gerät (`playlists` ohne Server); Server-Playlists kommen mit dem Abgleich. */
@Entity(tableName = "playlists")
data class UserPlaylistEntity(
    /** `playlists.backupKey` der Hauptdatenbank. */
    @PrimaryKey val playlistKey: String,
    val name: String,
    /** Stand der Playlist; nur wenn er sich ändert, wird die gesicherte Fassung ersetzt. */
    val updatedAt: Long,
)

@Entity(tableName = "playlist_entries", primaryKeys = ["playlistKey", "position"])
data class UserPlaylistEntryEntity(
    val playlistKey: String,
    val position: Int,
    @Embedded(prefix = "song_") val song: UserSongKey,
)

/** Favorit eines Songs ohne Server-Quelle; Server-Favoriten kommen mit dem Abgleich. */
@Entity(tableName = "favorites", primaryKeys = ["song_albumArtist", "song_album", "song_disc", "song_track", "song_title"])
data class UserFavoriteEntity(
    @Embedded(prefix = "song_") val song: UserSongKey,
    val changedAt: Long,
)

/**
 * Favorit eines Albums oder Künstlers ohne Server-Quelle; die anderen hält der Server. Alben über [artist] =
 * Album-Künstler und [title], Künstler über [artist] = Name (und leeren [title]), normalisiert wie beim Zusammenführen.
 */
@Entity(tableName = "collection_favorites", primaryKeys = ["kind", "artist", "title"])
data class UserCollectionFavoriteEntity(
    val kind: FavoriteKind,
    val artist: String,
    val title: String,
    val changedAt: Long,
)

/** Hörverlauf der Musik (`play_events`): „Zuletzt gehört“, „Wiederentdecken“. */
@Entity(
    tableName = "play_events",
    primaryKeys = ["song_albumArtist", "song_album", "song_disc", "song_track", "song_title", "playedAt"],
)
data class UserPlayEventEntity(
    @Embedded(prefix = "song_") val song: UserSongKey,
    val playedAt: Long,
    val listenedMs: Long,
)

/**
 * Was auf die Uhr soll (`wear_selections`). Ziel über [albumArtist]/[album] (normalisiert) bzw. [playlistRef]
 * (`local` + `backupKey` oder Server + `remotePlaylistId`) statt der `albumId`/`playlistId`.
 */
@Entity(tableName = "wear_selections")
data class UserWearSelectionEntity(
    @PrimaryKey val key: String,
    val kind: WearSelectionKind,
    val albumArtist: String?,
    val album: String?,
    val playlistRef: String?,
    val addedAt: Long,
)
