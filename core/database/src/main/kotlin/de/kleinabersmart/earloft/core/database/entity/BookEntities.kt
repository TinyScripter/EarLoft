package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload

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
    /** Ausgeschaltet: kein Abgleich, Bücher ausgeblendet; Bücher und Downloads bleiben erhalten. */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
)

/** Abonnierter Podcast oder Probeabo (Konzept 12.1, 12.10). Seine Folgen sind `books`-Zeilen mit `serverId = feedId`. */
@Entity(tableName = "podcast_feeds", indices = [Index("url", unique = true)])
data class PodcastFeedEntity(
    @PrimaryKey val feedId: String,
    val url: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    /** Validatoren des letzten Abrufs, damit ein unveränderter Feed nur 304 kostet. */
    val etag: String? = null,
    val lastModified: String? = null,
    val lastRefreshAt: Long = 0,
    /** Zähler für Mark-and-Sweep: Folgen, die der Feed nicht mehr enthält, fallen weg. */
    val syncGeneration: Long = 0,
    /** Neue Folgen automatisch herunterladen (je Podcast einstellbar). */
    val autoDownload: Boolean = false,
    /** „Alle abspielen“ und das Weiterspielen laufen von der neuesten Folge rückwärts statt von der ältesten vorwärts. */
    @ColumnInfo(defaultValue = "0") val newestFirst: Boolean = false,
    /** Nach dem Ende einer Folge startet von selbst die nächste. */
    @ColumnInfo(defaultValue = "1") val continuous: Boolean = true,
    /** Über neue Folgen dieses Podcasts benachrichtigen, wenn der Abgleich im Hintergrund läuft. */
    @ColumnInfo(defaultValue = "1") val notifyNew: Boolean = true,
    /** `false`: Probeabo aus der Vorschau (Konzept 12.10); fehlt in allen Abo-Abfragen und wird aufgeräumt. */
    @ColumnInfo(defaultValue = "1") val subscribed: Boolean = true,
    /**
     * Archiviertes Abo: fehlt wie ein Probeabo in allen Abo-Abfragen, wird nicht abgeglichen, behält aber Folgen,
     * Fortschritt und Statistik, bis es reaktiviert wird.
     */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** Webseite des Podcasts aus dem Feed, zum Teilen (Konzept 13). */
    val websiteUrl: String? = null,
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
    /** Cover der Folge bzw. des Buchs, wenn es nicht über den Server kommt (Podcasts). */
    val coverUrl: String? = null,
    /** Podcasting-2.0-Kapitelliste (JSON) einer Folge; `null`, wenn der Feed keine anbietet. */
    val chaptersUrl: String? = null,
    /** Webseite einer Folge aus dem Feed, zum Teilen (Konzept 13). */
    val webUrl: String? = null,
    /** ISBN eines Buchs aus Audiobookshelf, zum Teilen über Open Library (Konzept 13). */
    val isbn: String? = null,
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
    @ColumnInfo(defaultValue = "'NONE'") val downloadState: TrackDownload = TrackDownload.NONE,
    /** Heruntergeladene Datei, solange [downloadState] `DONE` ist. */
    val filePath: String? = null,
    @ColumnInfo(defaultValue = "0") val fileSize: Long = 0,
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
