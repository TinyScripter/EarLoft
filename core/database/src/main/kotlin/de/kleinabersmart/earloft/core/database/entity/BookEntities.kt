package de.kleinabersmart.earloft.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.ChapterSource
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.EpisodeType
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.TrackDownload

/** Audiobookshelf server (concept 12.1). The API key lives in the SecretStore, never here. */
@Entity(tableName = "spoken_servers")
data class SpokenServerEntity(
    @PrimaryKey val serverId: String,
    val name: String,
    val baseUrl: String,
    /**
     * The first synced library with `mediaType = book` (author images, recognition on restore); all book
     * libraries are in [SpokenLibraryEntity].
     */
    val libraryId: String? = null,
    /** Counter for mark-and-sweep during the full sync. */
    val syncGeneration: Long = 0,
    val lastFullSyncAt: Long = 0,
    /** Disabled: no sync, books hidden; books and downloads are kept. */
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    /** Logged in via OpenID instead of an API key (concept 12.11): tokens renew themselves; if they are missing, it says "Sign in again". */
    @ColumnInfo(defaultValue = "0") val openId: Boolean = false,
    /**
     * Deselected book libraries (v38, joined with ","): they are not synced and their books are hidden. New
     * libraries of the server are thus included automatically (`books_improvements.md` M3).
     */
    @ColumnInfo(defaultValue = "''") val hiddenLibraries: String = "",
) {
    val hiddenLibraryIds: Set<String> get() = hiddenLibraries.split(',').filterTo(HashSet()) { it.isNotBlank() }
}

/**
 * A book library of an Audiobookshelf server (v38), as the last sync saw it; for names and selection.
 * No foreign key: `upsertServer` replaces the server's row, and a cascade would take the libraries with it.
 */
@Entity(tableName = "spoken_libraries", primaryKeys = ["serverId", "libraryId"])
data class SpokenLibraryEntity(
    val serverId: String,
    val libraryId: String,
    val name: String,
    /** Order on the server. */
    val position: Int,
)

/**
 * An author of a book library of an Audiobookshelf server (v39), as the last sync saw them; for the
 * author page (`books_improvements.md` M8). No foreign key, like [SpokenLibraryEntity]. Books find the author by name
 * ([normName]), not by ID: `books.author` is the book's text.
 */
@Entity(tableName = "spoken_authors", primaryKeys = ["serverId", "authorId"], indices = [Index("normName")])
data class SpokenAuthorEntity(
    val serverId: String,
    val authorId: String,
    val name: String,
    val normName: String,
    val description: String? = null,
)

/** Subscribed podcast or trial subscription (concept 12.1, 12.10). Its episodes are `books` rows with `serverId = feedId`. */
@Entity(tableName = "podcast_feeds", indices = [Index("url", unique = true)])
data class PodcastFeedEntity(
    @PrimaryKey val feedId: String,
    val url: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val imageUrl: String? = null,
    /** Validators of the last fetch, so an unchanged feed only costs a 304. */
    val etag: String? = null,
    val lastModified: String? = null,
    val lastRefreshAt: Long = 0,
    /** Counter for mark-and-sweep: episodes the feed no longer contains are dropped. */
    val syncGeneration: Long = 0,
    /** Download new episodes automatically (configurable per podcast). */
    val autoDownload: Boolean = false,
    /** "Play all" and continued playback run backwards from the newest episode instead of forwards from the oldest. */
    @ColumnInfo(defaultValue = "0") val newestFirst: Boolean = false,
    /** After an episode ends, the next one starts automatically. */
    @ColumnInfo(defaultValue = "1") val continuous: Boolean = true,
    /** Notify about new episodes of this podcast when the sync runs in the background. */
    @ColumnInfo(defaultValue = "1") val notifyNew: Boolean = true,
    /** `false`: trial subscription from the preview (concept 12.10); missing from all subscription queries and gets cleaned up. */
    @ColumnInfo(defaultValue = "1") val subscribed: Boolean = true,
    /**
     * Archived subscription: like a trial subscription, missing from all subscription queries and not synced, but keeps episodes,
     * progress and statistics until it is reactivated.
     */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    /** Website of the podcast from the feed, for sharing (concept 13). */
    val websiteUrl: String? = null,
    /** Speed and effects for the episodes (schema v25); `null` = the section's default, an episode can have its own. */
    val speed: Float? = null,
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
    /** Seconds skipped at the start (only when starting at 0) or at the end of each episode. */
    @ColumnInfo(defaultValue = "0") val skipIntroS: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipOutroS: Int = 0,
    /** Filter and order of the episode list, what goes into "Continue listening" automatically, how many episodes stay visible (v27). */
    @ColumnInfo(defaultValue = "'ALL'") val episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
    @ColumnInfo(defaultValue = "0") val listOldestFirst: Boolean = false,
    @ColumnInfo(defaultValue = "'ALL_NEW'") val queueMode: FeedQueueMode = FeedQueueMode.ALL_NEW,
    @ColumnInfo(defaultValue = "0") val keepEpisodes: Int = 0,
    /** How many new episodes the automation keeps ready (v32); `null` = the default from the settings. */
    val autoDownloadCount: Int? = null,
    /**
     * Sync error status (v33): failed fetches in a row, reason of the last one, start of the streak (0 = works).
     * A successful sync resets everything; not in the backup.
     */
    @ColumnInfo(defaultValue = "0") val failedRefreshes: Int = 0,
    val lastError: FeedError? = null,
    @ColumnInfo(defaultValue = "0") val failingSince: Long = 0,
    /**
     * Podcasting 2.0 (v36): stable ID of the podcast (`podcast:guid`, protects feed moves) and the page for
     * support (`podcast:funding`) together with its text from the feed.
     */
    val podcastGuid: String? = null,
    val fundingUrl: String? = null,
    val fundingLabel: String? = null,
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
    /** The server's `updatedAt`; if it changes, chapters and files are reloaded. */
    val remoteUpdatedAt: Long = 0,
    val addedAt: Long = 0,
    /** Chapters and files are loaded (they only come with the individual fetch). */
    val detailsLoaded: Boolean = false,
    val seenInGeneration: Long = 0,
    /** Cover of the episode or book if it doesn't come via the server (podcasts). */
    val coverUrl: String? = null,
    /** Podcasting 2.0 chapter list (JSON) of an episode; `null` if the feed doesn't offer one. */
    val chaptersUrl: String? = null,
    /** Website of an episode from the feed, for sharing (concept 13). */
    val webUrl: String? = null,
    /** ISBN of a book from Audiobookshelf, for sharing via Open Library (concept 13). */
    val isbn: String? = null,
    /** Subtitle, publication year (text, as the server carries it), publisher and language of a book (schema v28). */
    val subtitle: String? = null,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    /**
     * Where an episode's chapters come from (v29); `null` = none yet, or for books those of the server or the file that
     * come with the files. Determines which source may replace them.
     */
    val chapterSource: ChapterSource? = null,
    /**
     * Episodes: starred (star, schema v34), since when; `null` = not starred. Belongs to the user, not the source: the
     * sync leaves the value alone ([de.kleinabersmart.earloft.core.database.dao.BookDao.upsertBook]), and starred
     * episodes stay even if the feed no longer carries them.
     */
    val starredAt: Long? = null,
    /** Episodes (v36): season and number, type from the feed (`null` = regular episode) and the transcript with MIME type. */
    val season: Int? = null,
    val episodeNumber: Int? = null,
    val episodeType: EpisodeType? = null,
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    /** Books (v37): genres from Audiobookshelf, individually and joined with ", "; `null` = none. */
    val genres: String? = null,
    /** Books of an Audiobookshelf server (v38): library the book comes from; otherwise `null`. */
    val libraryId: String? = null,
    /**
     * Books from ARD Sounds (v40, `books_improvements.md` M9): until when all parts are available (Unix ms, the earliest part);
     * `null` = unlimited or unknown.
     */
    val availableUntil: Long? = null,
)

/** Contributors of an episode from the feed (`podcast:person`, v36), in the feed's order. */
@Entity(
    tableName = "episode_persons",
    primaryKeys = ["bookId", "personIndex"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class EpisodePersonEntity(
    val bookId: Long,
    val personIndex: Int,
    val name: String,
    val role: String? = null,
    val personGroup: String? = null,
    val imageUrl: String? = null,
    val url: String? = null,
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
    /** Downloaded file, as long as [downloadState] is `DONE`. */
    val filePath: String? = null,
    @ColumnInfo(defaultValue = "0") val fileSize: Long = 0,
    /** Episodes: queued by the automation (v32), not manually; "Only the newest" cleans such ones up again. */
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
    /**
     * Books from ARD Sounds (v41): since when ARD Sounds no longer carries the part. It stays in its place so positions,
     * bookmarks and downloads remain correct; if not downloaded it is no longer available, and downloads skip it.
     */
    val unavailableSince: Long? = null,
    /**
     * Books from ARD Sounds (v44): the title's ID at ARD Sounds. It is used to find the book again when ARD Sounds
     * assigns its titles to a different group (the key in [BookEntity.remoteId] then leads nowhere).
     */
    val remoteItemId: String? = null,
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
    /** Image and website of the chapter (schema v30), only for episodes from a chapter list or Podlove. */
    val imageUrl: String? = null,
    val url: String? = null,
)

/** Progress per book (concept 12.3). `updatedAt` decides on conflicts, `dirty` means: not reported yet. */
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
    /** Own speed; `null` = that of the subscription or section (concept 12.4, nullable since schema v25). */
    val speed: Float? = null,
    val updatedAt: Long,
    val dirty: Boolean = false,
    /**
     * Removed from "Continue listening" (long press, or reset), for Audiobookshelf `hideFromContinueListening`. The
     * state stays; as soon as the book plays again, the flag is cleared (schema v24).
     */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** Own playback effects (schema v25); `null` = those of the subscription or section. */
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
    /**
     * When the book was finished (schema v42; v43 backfilled previously finished books from [updatedAt]); `null` = not
     * finished. Unlike [updatedAt] it doesn't advance when a finished book is merely removed from "Continue listening" or
     * synced.
     */
    val finishedAt: Long? = null,
)

/**
 * Bookmark in a book or an episode (concept 12.6, schema v31). For books of an Audiobookshelf server, the server
 * knows it under [remoteTime] (seconds; Audiobookshelf identifies bookmarks by their time), `null` = not yet created
 * there. `dirty`: change not yet reported; `deleted`: deleted, but not yet on the server. Episodes and local books
 * stay on the device and are never `dirty`.
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
    /** Position within the whole book. */
    val positionMs: Long,
    val title: String,
    val note: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val remoteTime: Double? = null,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
)
