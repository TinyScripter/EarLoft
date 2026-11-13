package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.ChapterSource
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.FeedQueueMode
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
    /** Angemeldet per OpenID statt API-Key (Konzept 12.11): Tokens erneuern sich, fehlen sie, heißt es „Neu anmelden“. */
    @ColumnInfo(defaultValue = "0") val openId: Boolean = false,
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
    /** Tempo und Effekte für die Folgen (Schema v25); `null` = die Vorgabe des Bereichs, eine Folge kann eigene haben. */
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
    /** Sekunden, die am Anfang (nur beim Start bei 0) bzw. am Ende jeder Folge übersprungen werden. */
    @ColumnInfo(defaultValue = "0") val skipIntroS: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipOutroS: Int = 0,
    /** Filter und Reihenfolge der Folgenliste, was von selbst in „Weiterhören“ kommt, wie viele Folgen sichtbar bleiben (v27). */
    @ColumnInfo(defaultValue = "'ALL'") val episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
    @ColumnInfo(defaultValue = "0") val listOldestFirst: Boolean = false,
    @ColumnInfo(defaultValue = "'ALL_NEW'") val queueMode: FeedQueueMode = FeedQueueMode.ALL_NEW,
    @ColumnInfo(defaultValue = "0") val keepEpisodes: Int = 0,
    /** Wie viele neue Folgen die Automatik bereithält (v32); `null` = die Vorgabe aus den Einstellungen. */
    val autoDownloadCount: Int? = null,
    /**
     * Fehlerstatus des Abgleichs (v33): fehlgeschlagene Abrufe in Folge, Grund des letzten, Beginn der Serie (0 = klappt).
     * Ein erfolgreicher Abgleich setzt alles zurück; nicht im Backup.
     */
    @ColumnInfo(defaultValue = "0") val failedRefreshes: Int = 0,
    val lastError: FeedError? = null,
    @ColumnInfo(defaultValue = "0") val failingSince: Long = 0,
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
    /** Untertitel, Erscheinungsjahr (Text, wie der Server es führt), Verlag und Sprache eines Buchs (Schema v28). */
    val subtitle: String? = null,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    /**
     * Woher die Kapitel einer Folge stammen (v29); `null` = noch keine bzw. bei Büchern die des Servers oder der Datei, die
     * mit den Dateien kommen. Bestimmt, welche Quelle sie ersetzen darf.
     */
    val chapterSource: ChapterSource? = null,
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
    /** Folgen: von der Automatik eingereiht (v32), nicht von Hand; „Nur die neueste“ räumt solche wieder weg. */
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
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
    /** Bild und Webseite des Kapitels (Schema v30), nur bei Folgen aus Kapitelliste oder Podlove. */
    val imageUrl: String? = null,
    val url: String? = null,
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
    /** Eigenes Tempo; `null` = das des Abos bzw. Bereichs (Konzept 12.4, seit Schema v25 nullbar). */
    val speed: Float? = null,
    val updatedAt: Long,
    val dirty: Boolean = false,
    /**
     * Aus „Weiterhören“ genommen (Langklick, oder zurückgesetzt), bei Audiobookshelf `hideFromContinueListening`. Der
     * Stand bleibt; sobald das Buch wieder läuft, fällt der Merker (Schema v24).
     */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** Eigene Wiedergabe-Effekte (Schema v25); `null` = die des Abos bzw. Bereichs. */
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
)

/**
 * Lesezeichen in einem Buch oder einer Folge (Konzept 12.6, Schema v31). Bei Büchern eines Audiobookshelf-Servers kennt
 * der Server es unter [remoteTime] (Sekunden; Audiobookshelf erkennt Lesezeichen an der Zeit), `null` = dort noch nicht
 * angelegt. `dirty`: Änderung noch nicht gemeldet; `deleted`: gelöscht, beim Server noch nicht. Folgen und lokale Bücher
 * bleiben auf dem Gerät und sind nie `dirty`.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val bookmarkId: Long = 0,
    val bookId: Long,
    /** Position im ganzen Buch. */
    val positionMs: Long,
    val title: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val remoteTime: Double? = null,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
)
