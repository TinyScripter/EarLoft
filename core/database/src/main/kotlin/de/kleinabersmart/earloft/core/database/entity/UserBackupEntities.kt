package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.BookKind
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

/** Ein Audiobookshelf-Server (`spoken_servers`). Der API-Key kommt nicht mit. */
@Entity(tableName = "spoken_servers")
data class UserSpokenServerEntity(
    @PrimaryKey val serverId: String,
    val name: String,
    val baseUrl: String,
    val libraryId: String?,
    val enabled: Boolean,
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
    val speed: Float,
    val updatedAt: Long,
    val dirty: Boolean,
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
