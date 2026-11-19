package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.LyricsKind
import de.kleinabersmart.earloft.core.model.SyncActionType

/** Single source of truth for the download status (no parallel Media3 download index). */
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
    /** Who triggered the download, e.g. "album:12", "playlist:3", "manual". */
    val pinnedBy: String? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String,
    /** `null` = local only; otherwise synced with exactly one server. */
    val boundSourceId: String? = null,
    val remotePlaylistId: String? = null,
    val updatedAt: Long,
    val remoteUpdatedAt: Long? = null,
    /** ID of local playlists in the backup (`user.db`, review K9); a new device assigns a new `playlistId`. */
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
    /** A separate ID allows duplicates in a playlist and reordering without PK conflicts. */
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
    /** Where the lyrics come from, e.g. "embedded" or a sourceId. */
    val origin: String,
    val fetchedAt: Long,
)

/** Persisted queue; index/position/shuffle/repeat live in the DataStore. */
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
    /** Song source UID or local playlistId – a remote ID may not exist yet. */
    val targetKey: String,
    val payloadJson: String? = null,
    val createdAt: Long,
    val attempts: Int = 0,
)
