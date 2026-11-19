package de.kleinabersmart.earloft.core.model

/** Spoken content (concept 12): an audiobook or a podcast episode. */
enum class BookKind { BOOK, EPISODE }

/**
 * Download state of a file (concept 12). [REMOVED]: removed by the user – the automation doesn't download it
 * again, manually it still works.
 */
enum class TrackDownload { NONE, QUEUED, RUNNING, DONE, FAILED, REMOVED }

/**
 * Where an episode's chapters come from, in ascending rank (concept 12.5 "chapter sources"): from the downloaded file
 * (ID3 `CHAP`, MP4), from the feed (Podlove Simple Chapters) or from the feed's chapter list (`podcast:chapters`, JSON).
 * A source only replaces chapters of the same or lower rank.
 */
enum class ChapterSource { FILE, FEED, JSON }

/** `serverId` of the books from the "Audiobooks" folder on this device (concept 12.1); there is no server for them. */
const val LOCAL_BOOK_SERVER_ID = "local"

/**
 * Prefix of the `feedId` of the podcasts from the "Podcasts" folder on this device (concept 12.1): one subscription per
 * podcast without a feed URL, whose episodes are the files.
 */
const val LOCAL_PODCAST_PREFIX = "local:"

/** Books and episodes from the device have no server: no download, no cast, progress only local. */
fun isLocalSource(serverId: String): Boolean = serverId == LOCAL_BOOK_SERVER_ID || serverId.startsWith(LOCAL_PODCAST_PREFIX)

/**
 * `serverId` of the audiobooks and radio plays from ARD Sounds (`books_improvements.md` M9). There is no server as with Audiobookshelf:
 * the file ID is the file's URL (as with episodes), progress and bookmarks stay on the device; downloading and casting
 * work.
 */
const val ARD_BOOK_SERVER_ID = "ardsounds"

fun isArdSoundsBook(serverId: String): Boolean = serverId == ARD_BOOK_SERVER_ID

/** Books without an Audiobookshelf server (from the device, from ARD Sounds): progress, bookmarks and sessions stay here. */
fun hasNoBookServer(serverId: String): Boolean = isLocalSource(serverId) || isArdSoundsBook(serverId)

/** Switch in the top bar of Home and Library (concept 12.5). */
enum class SpokenMode { MUSIC, PODCASTS, BOOKS }

data class Book(
    val id: Long,
    val serverId: String,
    val kind: BookKind,
    val title: String,
    val author: String,
    val narrator: String = "",
    val series: String = "",
    val description: String = "",
    val durationMs: Long,
    /** Entry on the server (for Audiobookshelf the item ID); `null` for local books. */
    val remoteId: String,
    val addedAt: Long = 0,
    /** Details from Audiobookshelf for the book page; the year as text, as the server stores it (usually "2014"). */
    val subtitle: String? = null,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    /** Episodes: since when bookmarked (star); `null` = not bookmarked. */
    val starredAt: Long? = null,
    /** Episodes: season, number and type from the feed (`null` = not specified or a regular episode). */
    val season: Int? = null,
    val episodeNumber: Int? = null,
    val episodeType: EpisodeType? = null,
    /** Episodes: URL of the transcript (`podcast:transcript`); `null` = none. */
    val transcriptUrl: String? = null,
    /** Books: genres from Audiobookshelf, split individually ([parseGenres]). */
    val genres: List<String> = emptyList(),
    /** Books of an Audiobookshelf server: the library there. */
    val libraryId: String? = null,
    /** Books from ARD Sounds: until when all parts are available (Unix ms); `null` = unlimited or unknown. */
    val availableUntil: Long? = null,
    /** Web page of the book or episode (ARD Sounds, feed). */
    val webUrl: String? = null,
) {
    /** Lives on this device: no server, no download, no cast. */
    val isLocal: Boolean get() = isLocalSource(serverId)

    /** From ARD Sounds (`books_improvements.md` M9). */
    val isArdSounds: Boolean get() = isArdSoundsBook(serverId)

    val starred: Boolean get() = starredAt != null
}

/**
 * One series of a book, with volume if known: [volume] as written ("2", "2a", "1-3", "1,5"), [sequence] the
 * order derived from it ([volumeSequence]); `null` if the volume doesn't start with a number.
 */
data class SeriesRef(val name: String, val sequence: Double?, val volume: String? = null)

/**
 * Audiobookshelf delivers the series name together with the volume ("Carl Mørck #10", multiple series separated by ", ", after the
 * volume). Here both are split; everything after " #" is the volume (Audiobookshelf allows arbitrary text there), a "#"
 * with nothing after it belongs to the name.
 */
fun parseSeries(raw: String): List<SeriesRef> =
    raw.split(SERIES_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { part ->
            SERIES_PART.find(part)?.let {
                val volume = it.groupValues[2]
                SeriesRef(it.groupValues[1].trim(), volumeSequence(volume), volume)
            } ?: SeriesRef(part, null)
        }

/**
 * Order of a volume: the leading number ("1,5" like "1.5"). If something follows ("2a", "1-3"), it comes right
 * after the volume with the bare number, i.e. "2", "2a", "3". `null` without a leading number ("IV").
 */
fun volumeSequence(volume: String): Double? {
    val match = VOLUME_NUMBER.find(volume.trim()) ?: return null
    val number = match.groupValues[1].replace(',', '.').toDouble()
    return if (match.groupValues[2].isBlank()) number else number + VOLUME_SUFFIX_STEP
}

private val SERIES_PART = Regex("""^(.*)\s+#\s*(\S.*?)\s*$""")

/** Between two series there is ", " after the volume; "1,5" without a space stays one volume. */
private val SERIES_SEPARATOR = Regex("""(?<=#[^\s#]{1,20}),\s+""")
private val VOLUME_NUMBER = Regex("""^(\d+(?:[.,]\d+)?)(.*)$""")
private const val VOLUME_SUFFIX_STEP = 0.001

/**
 * The individual names of an author or narrator entry: Audiobookshelf joins several with ", " (`authorName`,
 * `narratorName`), tags of local files also with ";". "A & B" stays together (often a duo). Empty and duplicate
 * names are dropped, the order is kept.
 */
fun parsePeople(raw: String): List<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * The individual genres of a book: Audiobookshelf keeps a list, but entries taken from metadata often contain
 * several in one ("Polizei, Thriller"). Split like [parsePeople]; "Politik & Regierungen" stays one.
 */
fun parseGenres(raw: List<String>): List<String> = raw.flatMap(::parsePeople).distinct()

/**
 * Whether [name] names a role ("Günther Frauenlob - Übersetzer", "Ruggero Leò - translator", "Anna Muster (Hrsg.)", "translated
 * by …"): Audiobookshelf lists translators, editors etc. as authors with the role in the name. Only what follows
 * " - " or is in parentheses is checked, so that a surname like "Sprecher" isn't a role.
 */
fun hasContributorRole(name: String): Boolean {
    if (ROLE_PREFIX.containsMatchIn(name.trim())) return true
    val notes = buildList {
        name.split(ROLE_DASH, limit = 2).getOrNull(1)?.let(::add)
        ROLE_BRACKET.findAll(name).forEach { add(it.groupValues[1]) }
    }
    return notes.any { note ->
        note.lowercase().split(NON_LETTERS).any { word -> word in CONTRIBUTOR_WORDS || CONTRIBUTOR_STEMS.any(word::startsWith) }
    }
}

/**
 * The authors under which a book is listed in library, search and Android Auto: the names from [author] without a role
 * ([hasContributorRole]); if the book only names contributors with a role (editor of a collection), those.
 */
fun bookAuthors(author: String): List<String> {
    val all = parsePeople(author)
    return all.filterNot(::hasContributorRole).ifEmpty { all }
}

/**
 * Author line of a book for tiles, player, notification and Android Auto: the [bookAuthors], so that translators etc.
 * don't come first. The book page still shows all names including their role.
 */
fun bookAuthorLine(author: String): String = bookAuthors(author).joinToString(", ").ifEmpty { author.trim() }

/** Author line in lists: for books [bookAuthorLine], for episodes the feed's entry unchanged. */
val Book.authorLine: String get() = if (kind == BookKind.BOOK) bookAuthorLine(author) else author

private val ROLE_DASH = Regex("""\s[-–—]\s""")
private val ROLE_BRACKET = Regex("""[(\[]([^)\]]*)[)\]]""")
private val ROLE_PREFIX = Regex("""^(translated by|edited by|read by|narrated by|übersetzt von|herausgegeben von|gelesen von)\s""", RegexOption.IGNORE_CASE)
private val NON_LETTERS = Regex("""[^\p{L}]+""")
private val CONTRIBUTOR_WORDS = setOf("hrsg", "hg", "ed", "eds")
private val CONTRIBUTOR_STEMS = listOf(
    "translat", "übersetz", "narrat", "sprecher", "edit", "herausgeb", "illustrat", "foreword", "vorwort", "afterword", "nachwort",
    "einleitung", "introduction",
)

data class BookChapter(
    val index: Int,
    val title: String,
    /** Start and end as a position in the whole book. */
    val startMs: Long,
    val endMs: Long,
    /** Image and web page of the chapter (chapter list or Podlove of an episode). */
    val imageUrl: String? = null,
    val url: String? = null,
    /** Lies in a file that is no longer available ([BookTrack.unavailable]). */
    val unavailable: Boolean = false,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Bookmark in a book or episode (concept 12.6); [positionMs] is the position in the whole book. */
data class Bookmark(
    val id: Long,
    val bookId: Long,
    val positionMs: Long,
    val title: String,
    val note: String,
    val createdAt: Long,
)

/** One audio file of the book. An m4b is one track, an MP3 folder has many. */
data class BookTrack(
    val index: Int,
    val remoteFileId: String,
    /** Start of the file as a position in the whole book. */
    val startMs: Long,
    val durationMs: Long,
    val mimeType: String? = null,
    /** Downloaded file; only set once the download is finished. */
    val localPath: String? = null,
    /** Not downloaded and no longer available at the source (an expired part of a book from ARD Sounds); playback skips it. */
    val unavailable: Boolean = false,
)

data class BookProgress(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    /** The book's or episode's own speed; `null` = that of the subscription or section (see [EffectLayers]). */
    val speed: Float? = null,
    /** Time of the last change (Unix ms) – the newer side wins (concept 12.3). */
    val updatedAt: Long,
    /** Not yet reported to the server. */
    val dirty: Boolean = false,
    /** Removed from "Continue listening" without losing the state (Audiobookshelf: `hideFromContinueListening`). */
    val hidden: Boolean = false,
    /** Own effects; `null` = those of the subscription or section. */
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class BookWithProgress(val book: Book, val progress: BookProgress?)

/** A configured Audiobookshelf server (without credentials). */
data class SpokenServer(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val lastFullSyncAt: Long,
    /** Switched off: no sync, books hidden, nothing deleted. */
    val enabled: Boolean = true,
    /** Signed in via OpenID instead of an API key. */
    val openId: Boolean = false,
    /** The server's book libraries in its order; with more than one you can choose (`books_improvements.md` M3). */
    val libraries: List<SpokenLibrary> = emptyList(),
)

/** A book library of an Audiobookshelf server; [shown] = is synced and shown. */
data class SpokenLibrary(val serverId: String, val libraryId: String, val name: String, val shown: Boolean)

/** An author from the search: their name, a book for the cover and the number of their books. */
data class AuthorHit(val name: String, val firstBookId: Long, val bookCount: Int)

/** A series from the search: its name, a book for the cover (the lowest volume) and the number of its books. */
data class SeriesHit(val name: String, val firstBookId: Long, val bookCount: Int)

/** A subscribed podcast (concept 12.1); its episodes are [Book]s with `kind = EPISODE` and `serverId = id`. */
data class PodcastFeed(
    val id: String,
    val url: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String?,
    val autoDownload: Boolean,
    val lastRefreshAt: Long,
    /** Order for "Play all" and continuing playback: newest first instead of oldest first. */
    val newestFirst: Boolean = false,
    /** Play the next episode automatically after one ends. */
    val continuous: Boolean = true,
    /** Notify about new episodes in the background. */
    val notifyNew: Boolean = true,
    /** `false`: trial subscription from the preview (concept 12.10) – episodes playable, but no subscription, no statistics. */
    val subscribed: Boolean = true,
    /** Archived: not listed anywhere, not synced; episodes, progress and statistics stay until reactivated. */
    val archived: Boolean = false,
    /** Speed and effects for the episodes (concept 12.4); `null` = the section's default. */
    val effects: EffectOverrides = EffectOverrides(),
    /** Seconds skipped at the start or end of every episode (0 = off). */
    val skipIntroS: Int = 0,
    val skipOutroS: Int = 0,
    /** Filter and order of the episodes on the subscription's page (display only). */
    val episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
    val listOldestFirst: Boolean = false,
    /** What goes into "Continue listening" automatically. */
    val queueMode: FeedQueueMode = FeedQueueMode.ALL_NEW,
    /** Only show the newest this many episodes, hide older never-started ones ([keepNewest]); 0 = all. */
    val keepEpisodes: Int = 0,
    /** How many new episodes the automation keeps ready; `null` = default from the settings. */
    val autoDownloadCount: Int? = null,
    /** Consecutive failed syncs, the reason for the last one and since when it has been failing (0 = works). */
    val failedRefreshes: Int = 0,
    val refreshError: FeedError? = null,
    val failingSince: Long = 0,
    /** Page for supporting the podcast (`podcast:funding`) and its text from the feed (may be empty). */
    val fundingUrl: String? = null,
    val fundingLabel: String = "",
) {
    /** From the "Podcasts" folder: no feed URL, no sync, no download, no subscription to end. */
    val isLocal: Boolean get() = id.startsWith(LOCAL_PODCAST_PREFIX)

    /** If the sync has been failing for [FEED_PROBLEM_AFTER] attempts, the app shows it (concept 12.10). */
    val hasProblem: Boolean get() = failedRefreshes >= FEED_PROBLEM_AFTER
}

/** From this many consecutive failed syncs on, a subscription counts as broken; a single failure is normal. */
const val FEED_PROBLEM_AFTER = 3

/** Why a feed's sync failed. */
enum class FeedError {
    /** 404/410: the feed no longer exists at that URL. */
    GONE,

    /** 401/403: the server requires a login or refuses the request. */
    DENIED,

    /** Other HTTP errors (usually 5xx). */
    SERVER,

    /** The response is not an RSS or Atom feed. */
    NOT_A_FEED,

    /** Certificate or TLS connection rejected. */
    TLS,

    /** Server not reachable (unknown host, timeout, connection refused). */
    UNREACHABLE,
}

/** Position within one of the book's files. */
data class TrackPosition(val trackIndex: Int, val offsetMs: Long)

/**
 * Converts between the position in the whole book and (file, position in the file) and provides chapters and
 * remaining times. The UI only shows book positions (concept 12.2).
 */
class BookTimeline(
    val durationMs: Long,
    tracks: List<BookTrack>,
    chapters: List<BookChapter>,
) {
    val tracks: List<BookTrack> = tracks.sortedBy { it.index }
    val chapters: List<BookChapter> = chapters.sortedBy { it.startMs }

    val hasChapters: Boolean get() = chapters.isNotEmpty()

    fun locate(positionMs: Long): TrackPosition {
        if (tracks.isEmpty()) return TrackPosition(0, positionMs.coerceAtLeast(0))
        val position = positionMs.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val track = tracks.lastOrNull { it.startMs <= position } ?: tracks.first()
        return TrackPosition(track.index, (position - track.startMs).coerceIn(0, track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }

    fun toBookPosition(trackIndex: Int, offsetMs: Long): Long {
        val track = tracks.firstOrNull { it.index == trackIndex } ?: return offsetMs.coerceAtLeast(0)
        return (track.startMs + offsetMs).coerceAtLeast(0)
    }

    fun chapterAt(positionMs: Long): BookChapter? {
        if (chapters.isEmpty()) return null
        return chapters.lastOrNull { it.startMs <= positionMs } ?: chapters.first()
    }

    fun nextChapter(positionMs: Long): BookChapter? = chapters.firstOrNull { it.startMs > positionMs }

    /**
     * Start of the chapter "previous" jumps to: if you're already more than [restartThresholdMs] into the chapter,
     * it is restarted, otherwise it goes to the previous one.
     */
    fun previousChapterStart(positionMs: Long, restartThresholdMs: Long = 3_000): Long? {
        val current = chapterAt(positionMs) ?: return null
        if (positionMs - current.startMs > restartThresholdMs) return current.startMs
        return chapters.lastOrNull { it.startMs < current.startMs }?.startMs ?: current.startMs
    }

    /** Remaining time of the book in playback time, i.e. divided by the speed. */
    fun remainingBookMs(positionMs: Long, speed: Float = 1f): Long =
        ((durationMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(MIN_SPEED)).toLong()

    /** Remaining time in the current chapter; `null` if the book has no chapters. */
    fun remainingChapterMs(positionMs: Long, speed: Float = 1f): Long? {
        val chapter = chapterAt(positionMs) ?: return null
        return ((chapter.endMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(MIN_SPEED)).toLong()
    }

    private companion object {
        const val MIN_SPEED = 0.1f
    }
}
