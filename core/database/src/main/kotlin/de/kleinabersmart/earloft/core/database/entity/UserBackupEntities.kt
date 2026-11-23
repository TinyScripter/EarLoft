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
 * Rows of the backed-up database `user.db` (review K9): only what no source restores, with keys that
 * still match on a new device. Sync states (generations, timestamps, ETags) are deliberately missing; they belong
 * to the device and start over after restoring.
 */

/** A music server (`sources`, Subsonic only). The password lives in the SecretStore and doesn't come along. */
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

/** An Audiobookshelf server (`spoken_servers`). API key or tokens don't come along, only whether it was signed in via OpenID. */
@Entity(tableName = "spoken_servers")
data class UserSpokenServerEntity(
    @PrimaryKey val serverId: String,
    val name: String,
    val baseUrl: String,
    val libraryId: String?,
    val enabled: Boolean,
    @ColumnInfo(defaultValue = "0") val openId: Boolean = false,
    /** Deselected book libraries (v17), as in the main database. */
    @ColumnInfo(defaultValue = "''") val hiddenLibraries: String = "",
)

/**
 * A subscribed (including archived) podcast (`podcast_feeds`) together with its settings; podcasts from the device are
 * rebuilt by the scan.
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
    /** Speed and effects of the subscription (since version 11), `null` = the section's default. */
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
    @ColumnInfo(defaultValue = "0") val skipIntroS: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipOutroS: Int = 0,
    /** Episode list and "Continue listening" (since version 13). */
    @ColumnInfo(defaultValue = "'ALL'") val episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
    @ColumnInfo(defaultValue = "0") val listOldestFirst: Boolean = false,
    @ColumnInfo(defaultValue = "'ALL_NEW'") val queueMode: FeedQueueMode = FeedQueueMode.ALL_NEW,
    @ColumnInfo(defaultValue = "0") val keepEpisodes: Int = 0,
    /** Keep ready per subscription (since version 15), `null` = default. */
    val autoDownloadCount: Int? = null,
)

/** Listening time per entry (`listening_stats`), via the natural key instead of the running `statId`. */
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

/** Listening time per day and group (`listening_days`). */
@Entity(tableName = "listening_days", primaryKeys = ["day", "kind", "groupKey"])
data class UserListeningDayEntity(
    val day: Long,
    val kind: BookKind,
    val groupKey: String,
    val listenedMs: Long,
    /** Saved by "Trim silence" (since version 12). */
    @ColumnInfo(defaultValue = "0") val savedMs: Long = 0,
)

/**
 * Progress of a book or an episode (`book_progress`) via the book's `(serverId, remoteId)` instead of the running
 * `bookId`: feed and guid, Audiobookshelf server and item, or "local" and the path in the folder. For Audiobookshelf books
 * only the states the server doesn't know yet (`dirty`); the sync brings back the rest.
 */
@Entity(tableName = "book_progress", primaryKeys = ["serverId", "remoteId"])
data class UserBookProgressEntity(
    val serverId: String,
    val remoteId: String,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    /** Own speed; `null` = that of the subscription or section (nullable since version 11). */
    val speed: Float?,
    val updatedAt: Long,
    val dirty: Boolean,
    /** Removed from "Continue listening" (since version 10). */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** Own effects (since version 11). */
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
)

/**
 * A bookmark (`bookmarks`, since version 14) via `(serverId, remoteId)` of its book and its position: Audiobookshelf
 * identifies bookmarks by their time, two at the same spot make no sense. Deleted ones are missing. For Audiobookshelf books
 * only those the server doesn't have yet (`remoteTime` missing or changed); the sync brings back the rest.
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
    /** Time on the server (seconds), `null` = not yet known there. */
    val remoteTime: Double?,
)

/**
 * What happened to a podcast episode's download (`book_tracks.downloadState`), via feed and guid. [wanted]: downloaded or
 * waiting – comes back on a new device as long as the episode hasn't been played. Otherwise removed or failed –
 * then the automation doesn't download it there either.
 */
@Entity(tableName = "episode_downloads", primaryKeys = ["serverId", "remoteId"])
data class UserEpisodeDownloadEntity(
    val serverId: String,
    val remoteId: String,
    val wanted: Boolean,
)

/** A starred podcast episode (`books.starredAt`, since version 16), via feed and guid. */
@Entity(tableName = "episode_stars", primaryKeys = ["serverId", "remoteId"])
data class UserEpisodeStarEntity(
    val serverId: String,
    val remoteId: String,
    val starredAt: Long,
)

/**
 * A book from ARD Sounds in the library (`books` with `serverId = ardsounds`, since version 18), via its ID at ARD Sounds.
 * The app fetches the book itself from there again on a new device; progress and bookmarks come via their own rows.
 * [parts] (since version 19) are its parts in their order, including those ARD Sounds no longer carries: without them,
 * position and bookmarks would shift on the new device as soon as a part expired (format: `ArdBookParts.kt` in `:core:data`).
 */
@Entity(tableName = "ard_books")
data class UserArdBookEntity(
    @PrimaryKey val remoteId: String,
    val addedAt: Long,
    val parts: String? = null,
)

/**
 * A song via its metadata instead of the `songId` (see `KeyedSong`): album artist, album, disc, track number and title,
 * normalized as when merging, and the album's [year] (since version 20; 0 = unknown, also for rows backed up before):
 * like the merge, the backup tells an original and its remaster apart.
 */
data class UserSongKey(
    val albumArtist: String,
    val album: String,
    val disc: Int,
    val track: Int,
    val title: String,
    @ColumnInfo(defaultValue = "0") val year: Int = 0,
)

/** A playlist only on this device (`playlists` without a server); server playlists come with the sync. */
@Entity(tableName = "playlists")
data class UserPlaylistEntity(
    /** `playlists.backupKey` of the main database. */
    @PrimaryKey val playlistKey: String,
    val name: String,
    /** State of the playlist; only when it changes is the backed-up version replaced. */
    val updatedAt: Long,
)

@Entity(tableName = "playlist_entries", primaryKeys = ["playlistKey", "position"])
data class UserPlaylistEntryEntity(
    val playlistKey: String,
    val position: Int,
    @Embedded(prefix = "song_") val song: UserSongKey,
)

/** Favorite of a song without a server source; server favorites come with the sync. */
@Entity(tableName = "favorites", primaryKeys = ["song_albumArtist", "song_album", "song_disc", "song_track", "song_title", "song_year"])
data class UserFavoriteEntity(
    @Embedded(prefix = "song_") val song: UserSongKey,
    val changedAt: Long,
)

/**
 * Favorite of an album or artist without a server source; the server holds the others. Albums via [artist] =
 * album artist, [title] and [year] (see [UserSongKey.year]), artists via [artist] = name (and empty [title], [year] 0),
 * normalized as when merging.
 */
@Entity(tableName = "collection_favorites", primaryKeys = ["kind", "artist", "title", "year"])
data class UserCollectionFavoriteEntity(
    val kind: FavoriteKind,
    val artist: String,
    val title: String,
    val changedAt: Long,
    @ColumnInfo(defaultValue = "0") val year: Int = 0,
)

/** Music listening history (`play_events`): "Recently played", "Rediscover". */
@Entity(
    tableName = "play_events",
    primaryKeys = ["song_albumArtist", "song_album", "song_disc", "song_track", "song_title", "song_year", "playedAt"],
)
data class UserPlayEventEntity(
    @Embedded(prefix = "song_") val song: UserSongKey,
    val playedAt: Long,
    val listenedMs: Long,
)

/**
 * What should go onto the watch (`wear_selections`). Target via [albumArtist]/[album] (normalized) and [year] (see
 * [UserSongKey.year]) or [playlistRef] (`local` + `backupKey` or server + `remotePlaylistId`) instead of the `albumId`/`playlistId`.
 */
@Entity(tableName = "wear_selections")
data class UserWearSelectionEntity(
    @PrimaryKey val key: String,
    val kind: WearSelectionKind,
    val albumArtist: String?,
    val album: String?,
    val playlistRef: String?,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val year: Int = 0,
)
