package keinabersmart.kleinaberlaut.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import keinabersmart.kleinaberlaut.core.model.DownloadState
import keinabersmart.kleinaberlaut.core.model.LyricsKind
import keinabersmart.kleinaberlaut.core.model.SyncActionType

/** Einzige Wahrheit für den Download-Status (kein paralleler Media3-Download-Index). */
@Entity(
    tableName = "downloads",
    foreignKeys = [
        ForeignKey(
            entity = SongSourceEntity::class,
            parentColumns = ["uid"],
            childColumns = ["sourceUid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId")],
)
data class DownloadEntity(
    @PrimaryKey val sourceUid: String,
    val songId: Long,
    val state: DownloadState,
    val filePath: String? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null,
    /** Wer den Download ausgelöst hat, z. B. "album:12", "playlist:3", "manual". */
    val pinnedBy: String? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    /** `null` = nur lokal; sonst mit genau einem Server synchronisiert. */
    val boundSourceId: String? = null,
    val remotePlaylistId: String? = null,
    val updatedAt: Long,
    val remoteUpdatedAt: Long? = null,
    /** Kennung lokaler Playlists in der Sicherung (`user.db`, Review K9); die `playlistId` vergibt ein neues Gerät neu. */
    @ColumnInfo(defaultValue = "NULL") val backupKey: String? = null,
)

@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId", "position"), Index("songId")],
)
data class PlaylistEntryEntity(
    /** Eigene ID erlaubt Duplikate in einer Playlist und Umsortieren ohne PK-Konflikte. */
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

@Entity(
    tableName = "lyrics",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val kind: LyricsKind,
    val content: String,
    /** Woher die Lyrics stammen, z. B. "embedded" oder eine sourceId. */
    val origin: String,
    val fetchedAt: Long,
)

/** Persistierte Warteschlange; Index/Position/Shuffle/Repeat liegen im DataStore. */
@Entity(
    tableName = "queue_items",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId")],
)
data class QueueItemEntity(
    @PrimaryKey val position: Int,
    val songId: Long,
)

@Entity(
    tableName = "play_events",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId"), Index("playedAt")],
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
    val listenedMs: Long,
)

@Entity(
    tableName = "sync_actions",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["sourceId"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId", "actionType", "targetKey")],
)
data class SyncActionEntity(
    @PrimaryKey(autoGenerate = true) val actionId: Long = 0,
    val sourceId: String,
    val actionType: SyncActionType,
    /** Song-Source-UID bzw. lokale playlistId – eine Remote-ID existiert evtl. noch nicht. */
    val targetKey: String,
    val payloadJson: String? = null,
    val createdAt: Long,
    val attempts: Int = 0,
)
