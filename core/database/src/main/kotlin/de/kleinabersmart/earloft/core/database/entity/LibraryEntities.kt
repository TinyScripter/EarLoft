package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.SourceType

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val sourceId: String,
    val type: SourceType,
    val name: String,
    val baseUrl: String? = null,
    /** The secret is stored encrypted in the SecretStore, not in the database. */
    val username: String? = null,
    /** Choice among several streaming sources for the same song; higher = preferred. */
    val priority: Int = 0,
    /** OpenSubsonic extensions as JSON. */
    val serverCapabilities: String? = null,
    /** Counter for mark-and-sweep during the full sync. */
    val syncGeneration: Long = 0,
    val lastFullSyncAt: Long = 0,
    /** Disabled: no sync, content hidden; songs and downloads are kept. */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
)

@Entity(
    tableName = "artists",
    indices = [Index("normName"), Index("mbid")],
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val artistId: Long = 0,
    val name: String,
    val sortName: String,
    val normName: String,
    val mbid: String? = null,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favoriteChangedAt: Long = 0,
)

@Entity(
    tableName = "albums",
    indices = [Index("normAlbumArtist", "normTitle"), Index("mbid"), Index("year")],
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val albumId: Long = 0,
    val title: String,
    val sortTitle: String,
    val normTitle: String,
    val albumArtistDisplay: String,
    val normAlbumArtist: String,
    val year: Int? = null,
    val isCompilation: Boolean = false,
    /** MusicBrainz Release ID. */
    val mbid: String? = null,
    /** `song_sources.uid` whose cover is used for the album. */
    val coverSourceUid: String? = null,
    /** Seed color computed once from the cover (ARGB). */
    val seedColor: Int? = null,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favoriteChangedAt: Long = 0,
)

/** Logical song as the user sees it – regardless of how many sources provide it. */
@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("albumId"), Index("mbid"), Index("normTitle"), Index("genre")],
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val songId: Long = 0,
    val albumId: Long,
    val title: String,
    val normTitle: String,
    val artistDisplay: String,
    val discNumber: Int = 1,
    val trackNumber: Int = 0,
    val durationMs: Long,
    val year: Int? = null,
    /** The first genre (watch, display); all of them are in [SongGenreEntity]. */
    val genre: String? = null,
    /** MusicBrainz Recording ID. */
    val mbid: String? = null,
    val isFavorite: Boolean = false,
    val favoriteChangedAt: Long = 0,
    val dateAdded: Long,
)

/** Physical file or server entry that provides a [SongEntity]. */
@Entity(
    tableName = "song_sources",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId"), Index(value = ["sourceId", "remoteId"], unique = true)],
)
data class SongSourceEntity(
    /** `"$sourceId:$remoteId"` – ID that is stable across devices. */
    @PrimaryKey val uid: String,
    val songId: Long,
    val sourceId: String,
    val remoteId: String,
    val coverRemoteId: String? = null,
    val mimeType: String? = null,
    val bitRate: Int? = null,
    val sizeBytes: Long? = null,
    val lastModified: Long? = null,
    val rgTrackGainDb: Float? = null,
    val rgTrackPeak: Float? = null,
    val rgAlbumGainDb: Float? = null,
    val rgAlbumPeak: Float? = null,
    val seenInGeneration: Long,
    /** Time at which the server favorited the song; `null` = not favorited (local sources always). */
    @ColumnInfo(defaultValue = "NULL") val starredAt: Long? = null,
)

/**
 * A genre of a song; a song can have several (OpenSubsonic `genres`, Jellyfin/Emby `Genres`). [genreKey] is
 * `Genre.key`: spellings that differ only in case are one genre (including umlauts, which
 * SQLite with `NOCASE` does not treat as equal). [position] 0 is the genre in [SongEntity.genre].
 */
@Entity(
    tableName = "song_genres",
    primaryKeys = ["songId", "genreKey"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("genreKey")],
)
data class SongGenreEntity(
    val songId: Long,
    val genre: String,
    val genreKey: String,
    val position: Int,
)

@Entity(
    tableName = "song_artists",
    primaryKeys = ["songId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["artistId"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId")],
)
data class SongArtistCrossRef(
    val songId: Long,
    val artistId: Long,
    val position: Int,
)

@Entity(
    tableName = "album_artists",
    primaryKeys = ["albumId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["artistId"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId")],
)
data class AlbumArtistCrossRef(
    val albumId: Long,
    val artistId: Long,
    val position: Int,
)

/**
 * An album as a server carries it under its own ID (for favorites, concept 6.4). Several server albums can be merged into
 * one album. Filled from the songs of each sync; the full sync deletes older generations.
 */
@Entity(
    tableName = "album_sources",
    primaryKeys = ["sourceId", "remoteId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("albumId")],
)
data class AlbumSourceEntity(
    val sourceId: String,
    val remoteId: String,
    val albumId: Long,
    val seenInGeneration: Long,
    /** Like [SongSourceEntity.starredAt]. */
    val starredAt: Long? = null,
)

/** Like [AlbumSourceEntity] for artists. */
@Entity(
    tableName = "artist_sources",
    primaryKeys = ["sourceId", "remoteId"],
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["artistId"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId")],
)
data class ArtistSourceEntity(
    val sourceId: String,
    val remoteId: String,
    val artistId: Long,
    val seenInGeneration: Long,
    val starredAt: Long? = null,
)
