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
    /** Secret liegt verschlüsselt im SecretStore, nicht in der Datenbank. */
    val username: String? = null,
    /** Auswahl bei mehreren Streaming-Quellen für denselben Song; höher = bevorzugt. */
    val priority: Int = 0,
    /** OpenSubsonic-Extensions als JSON. */
    val serverCapabilities: String? = null,
    /** Zähler für Mark-and-Sweep beim Vollabgleich. */
    val syncGeneration: Long = 0,
    val lastFullSyncAt: Long = 0,
    /** Ausgeschaltet: kein Abgleich, Inhalte ausgeblendet; Songs und Downloads bleiben erhalten. */
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
    /** `song_sources.uid`, deren Cover für das Album genutzt wird. */
    val coverSourceUid: String? = null,
    /** Einmalig aus dem Cover berechnete Seed-Farbe (ARGB). */
    val seedColor: Int? = null,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favoriteChangedAt: Long = 0,
)

/** Logischer Song, wie ihn der Nutzer sieht – unabhängig davon, wie viele Quellen ihn liefern. */
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
    val genre: String? = null,
    /** MusicBrainz Recording ID. */
    val mbid: String? = null,
    val isFavorite: Boolean = false,
    val favoriteChangedAt: Long = 0,
    val dateAdded: Long,
)

/** Physische Datei bzw. Server-Eintrag, der einen [SongEntity] liefert. */
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
    /** `"$sourceId:$remoteId"` – geräteübergreifend stabile Kennung. */
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
    /** Zeitpunkt, zu dem der Server den Song favorisiert hat; `null` = nicht favorisiert (lokale Quellen immer). */
    @ColumnInfo(defaultValue = "NULL") val starredAt: Long? = null,
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
 * Ein Album, wie es ein Server unter eigener ID führt (für Favoriten, Konzept 6.4). Mehrere Server-Alben können zu einem
 * Album zusammengeführt sein. Gefüllt aus den Songs jedes Abgleichs; ältere Generationen löscht der Vollabgleich.
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
    /** Wie [SongSourceEntity.starredAt]. */
    val starredAt: Long? = null,
)

/** Wie [AlbumSourceEntity] für Künstler. */
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
