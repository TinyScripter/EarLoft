package keinabersmart.kleinaberlaut.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import keinabersmart.kleinaberlaut.core.model.BookKind

/** Audiobookshelf-Server (Konzept 12.1). Der API-Key liegt im SecretStore, nie hier. */
@Entity(tableName = "spoken_servers")
data class SpokenServerEntity(
    @PrimaryKey val serverId: String,
    val name: String,
    val baseUrl: String,
    /** Bibliothek mit `mediaType = book`, aus der abgeglichen wird. */
    val libraryId: String? = null,
    /** Zähler für Mark-and-Sweep beim Vollabgleich. */
    val syncGeneration: Long = 0,
    val lastFullSyncAt: Long = 0,
)

@Entity(
    tableName = "books",
    indices = [Index("serverId", "remoteId", unique = true), Index("normTitle")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val bookId: Long = 0,
    val serverId: String,
    val kind: BookKind = BookKind.BOOK,
    val remoteId: String,
    val title: String,
    val normTitle: String,
    val author: String,
    val narrator: String = "",
    val series: String = "",
    val description: String = "",
    val durationMs: Long,
    /** `updatedAt` des Servers; ändert er sich, werden Kapitel und Dateien neu geladen. */
    val remoteUpdatedAt: Long = 0,
    val addedAt: Long = 0,
    /** Kapitel und Dateien sind geladen (kommen nur mit dem Einzelabruf). */
    val detailsLoaded: Boolean = false,
    val seenInGeneration: Long = 0,
)

@Entity(
    tableName = "book_tracks",
    primaryKeys = ["bookId", "trackIndex"],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookTrackEntity(
    val bookId: Long,
    val trackIndex: Int,
    val remoteFileId: String,
    val startMs: Long,
    val durationMs: Long,
    val mimeType: String? = null,
)

@Entity(
    tableName = "book_chapters",
    primaryKeys = ["bookId", "chapterIndex"],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookChapterEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/** Fortschritt je Buch (Konzept 12.3). `updatedAt` entscheidet bei Konflikten, `dirty` heißt: noch nicht gemeldet. */
@Entity(
    tableName = "book_progress",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookProgressEntity(
    @PrimaryKey val bookId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean = false,
    val speed: Float = 1f,
    val updatedAt: Long,
    val dirty: Boolean = false,
)
