package de.kleinabersmart.earloft.core.model

/** Gesprochenes (Konzept 12): ein Hörbuch oder eine Podcast-Folge. */
enum class BookKind { BOOK, EPISODE }

/**
 * Zustand des Downloads einer Datei (Konzept 12). [REMOVED]: vom Nutzer entfernt – die Automatik lädt sie nicht
 * wieder, von Hand geht es weiterhin.
 */
enum class TrackDownload { NONE, QUEUED, RUNNING, DONE, FAILED, REMOVED }

/**
 * Woher die Kapitel einer Folge stammen, in aufsteigendem Rang (Konzept 12.5 „Kapitelquellen“): aus der geladenen Datei
 * (ID3 `CHAP`, MP4), aus dem Feed (Podlove Simple Chapters) oder aus der Kapitelliste des Feeds (`podcast:chapters`, JSON).
 * Eine Quelle ersetzt nur Kapitel gleichen oder niedrigeren Rangs.
 */
enum class ChapterSource { FILE, FEED, JSON }

/** `serverId` der Bücher aus dem Ordner „Audiobooks“ auf diesem Gerät (Konzept 12.1); es gibt dazu keinen Server. */
const val LOCAL_BOOK_SERVER_ID = "local"

/**
 * Anfang der `feedId` der Podcasts aus dem Ordner „Podcasts“ auf diesem Gerät (Konzept 12.1): Je Podcast ein Abo ohne
 * Feed-Adresse, dessen Folgen die Dateien sind.
 */
const val LOCAL_PODCAST_PREFIX = "local:"

/** Bücher und Folgen vom Gerät haben keinen Server: kein Download, kein Cast, Fortschritt nur lokal. */
fun isLocalSource(serverId: String): Boolean = serverId == LOCAL_BOOK_SERVER_ID || serverId.startsWith(LOCAL_PODCAST_PREFIX)

/** Schalter in der oberen Leiste von Start und Bibliothek (Konzept 12.5). */
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
    /** Eintrag auf dem Server (bei Audiobookshelf die Item-ID); `null` bei lokalen Büchern. */
    val remoteId: String,
    val addedAt: Long = 0,
    /** Angaben von Audiobookshelf für die Buchseite; das Jahr als Text, wie der Server es führt (meist „2014“). */
    val subtitle: String? = null,
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    /** Folgen: seit wann gemerkt (Stern); `null` = nicht gemerkt. */
    val starredAt: Long? = null,
    /** Folgen: Staffel, Nummer und Art aus dem Feed (`null` = keine Angabe bzw. normale Folge). */
    val season: Int? = null,
    val episodeNumber: Int? = null,
    val episodeType: EpisodeType? = null,
    /** Folgen: Adresse des Transkripts (`podcast:transcript`); `null` = keins. */
    val transcriptUrl: String? = null,
) {
    /** Liegt auf diesem Gerät: kein Server, kein Download, kein Cast. */
    val isLocal: Boolean get() = isLocalSource(serverId)

    val starred: Boolean get() = starredAt != null
}

/** Eine Serie eines Buchs, mit Band, falls der Server ihn mitliefert. */
data class SeriesRef(val name: String, val sequence: Double?)

/**
 * Audiobookshelf liefert den Serienamen samt Band („Carl Mørck #10“, mehrere Serien durch „, “ getrennt, nach dem
 * Band). Hier wird beides getrennt; ein „#“ ohne Zahl gehört zum Namen.
 */
fun parseSeries(raw: String): List<SeriesRef> =
    raw.split(SERIES_SEPARATOR)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { part ->
            SERIES_PART.find(part)?.let { SeriesRef(it.groupValues[1].trim(), it.groupValues[2].toDouble()) } ?: SeriesRef(part, null)
        }

private val SERIES_PART = Regex("""^(.*?)\s+#(\d+(?:\.\d+)?)$""")
private val SERIES_SEPARATOR = Regex("""(?<=#[\d.]{1,10}),\s*""")

/**
 * Die einzelnen Namen eines Autoren- bzw. Sprechereintrags: Audiobookshelf verbindet mehrere mit „, “ (`authorName`,
 * `narratorName`), Tags lokaler Dateien auch mit „;“. „A & B“ bleibt beisammen (oft ein Duo). Leere und doppelte
 * Namen entfallen, die Reihenfolge bleibt.
 */
fun parsePeople(raw: String): List<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Ob [name] eine Rolle nennt („Günther Frauenlob - Übersetzer“, „Ruggero Leò - translator“): Audiobookshelf führt
 * Übersetzer, Herausgeber u. Ä. als Autoren mit der Rolle im Namen.
 */
fun hasContributorRole(name: String): Boolean = CONTRIBUTOR_ROLES.any { name.contains(it, ignoreCase = true) }

/**
 * Die Autoren, unter denen ein Buch in Bibliothek, Suche und Android Auto steht: die Namen aus [author] ohne Rolle
 * ([hasContributorRole]); nennt das Buch nur Mitwirkende mit Rolle (Herausgeber einer Sammlung), diese.
 */
fun bookAuthors(author: String): List<String> {
    val all = parsePeople(author)
    return all.filterNot(::hasContributorRole).ifEmpty { all }
}

/**
 * Autorenzeile eines Buchs für Kacheln, Player, Benachrichtigung und Android Auto: die [bookAuthors], damit Übersetzer u. Ä.
 * nicht vorn stehen. Die Buchseite zeigt weiter alle Namen samt Rolle.
 */
fun bookAuthorLine(author: String): String = bookAuthors(author).joinToString(", ").ifEmpty { author.trim() }

/** Autorenzeile in Listen: bei Büchern [bookAuthorLine], bei Folgen der Eintrag des Feeds unverändert. */
val Book.authorLine: String get() = if (kind == BookKind.BOOK) bookAuthorLine(author) else author

private val CONTRIBUTOR_ROLES =
    listOf("translator", "übersetz", "narrator", "sprecher", "editor", "herausgeber", "illustrator", "foreword", "vorwort")

data class BookChapter(
    val index: Int,
    val title: String,
    /** Beginn und Ende als Position im ganzen Buch. */
    val startMs: Long,
    val endMs: Long,
    /** Bild und Webseite des Kapitels (Kapitelliste oder Podlove einer Folge). */
    val imageUrl: String? = null,
    val url: String? = null,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Lesezeichen in einem Buch oder einer Folge (Konzept 12.6); [positionMs] ist die Position im ganzen Buch. */
data class Bookmark(
    val id: Long,
    val bookId: Long,
    val positionMs: Long,
    val title: String,
    val note: String,
    val createdAt: Long,
)

/** Eine Audiodatei des Buchs. Ein m4b ist ein Titel, ein MP3-Ordner hat viele. */
data class BookTrack(
    val index: Int,
    val remoteFileId: String,
    /** Beginn der Datei als Position im ganzen Buch. */
    val startMs: Long,
    val durationMs: Long,
    val mimeType: String? = null,
    /** Heruntergeladene Datei; nur gesetzt, wenn der Download fertig ist. */
    val localPath: String? = null,
)

data class BookProgress(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    /** Eigenes Tempo des Buchs bzw. der Folge; `null` = das des Abos bzw. Bereichs (siehe [EffectLayers]). */
    val speed: Float? = null,
    /** Zeitpunkt der letzten Änderung (Unix-ms) – die neuere Seite gewinnt (Konzept 12.3). */
    val updatedAt: Long,
    /** Noch nicht an den Server gemeldet. */
    val dirty: Boolean = false,
    /** Aus „Weiterhören“ genommen, ohne den Stand zu verlieren (Audiobookshelf: `hideFromContinueListening`). */
    val hidden: Boolean = false,
    /** Eigene Effekte; `null` = die des Abos bzw. Bereichs. */
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class BookWithProgress(val book: Book, val progress: BookProgress?)

/** Ein eingerichteter Audiobookshelf-Server (ohne Zugangsdaten). */
data class SpokenServer(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val lastFullSyncAt: Long,
    /** Ausgeschaltet: kein Abgleich, Bücher ausgeblendet, nichts gelöscht. */
    val enabled: Boolean = true,
    /** Angemeldet per OpenID statt API-Key. */
    val openId: Boolean = false,
)

/** Ein Autor aus der Suche: sein Name, ein Buch für das Cover und die Anzahl seiner Bücher. */
data class AuthorHit(val name: String, val firstBookId: Long, val bookCount: Int)

/** Eine Serie aus der Suche: ihr Name, ein Buch für das Cover (der kleinste Band) und die Anzahl ihrer Bücher. */
data class SeriesHit(val name: String, val firstBookId: Long, val bookCount: Int)

/** Ein abonnierter Podcast (Konzept 12.1); seine Folgen sind [Book]s mit `kind = EPISODE` und `serverId = id`. */
data class PodcastFeed(
    val id: String,
    val url: String,
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String?,
    val autoDownload: Boolean,
    val lastRefreshAt: Long,
    /** Reihenfolge für „Alle abspielen“ und das Weiterspielen: neueste zuerst statt älteste zuerst. */
    val newestFirst: Boolean = false,
    /** Nach einer Folge von selbst die nächste spielen. */
    val continuous: Boolean = true,
    /** Bei neuen Folgen im Hintergrund benachrichtigen. */
    val notifyNew: Boolean = true,
    /** `false`: Probeabo aus der Vorschau (Konzept 12.10) – Folgen hörbar, aber kein Abo, keine Statistik. */
    val subscribed: Boolean = true,
    /** Archiviert: nirgends gelistet, nicht abgeglichen; Folgen, Fortschritt und Statistik bleiben bis zum Reaktivieren. */
    val archived: Boolean = false,
    /** Tempo und Effekte für die Folgen (Konzept 12.4); `null` = Vorgabe des Bereichs. */
    val effects: EffectOverrides = EffectOverrides(),
    /** Sekunden, die am Anfang bzw. Ende jeder Folge übersprungen werden (0 = aus). */
    val skipIntroS: Int = 0,
    val skipOutroS: Int = 0,
    /** Filter und Reihenfolge der Folgen auf der Seite des Abos (nur die Anzeige). */
    val episodeFilter: EpisodeFilter = EpisodeFilter.ALL,
    val listOldestFirst: Boolean = false,
    /** Was von selbst in „Weiterhören“ kommt. */
    val queueMode: FeedQueueMode = FeedQueueMode.ALL_NEW,
    /** Nur die neuesten so vielen Folgen zeigen, ältere nie angefangene ausblenden ([keepNewest]); 0 = alle. */
    val keepEpisodes: Int = 0,
    /** Wie viele neue Folgen die Automatik bereithält; `null` = Vorgabe aus den Einstellungen. */
    val autoDownloadCount: Int? = null,
    /** Fehlgeschlagene Abgleiche in Folge, der Grund des letzten und seit wann es scheitert (0 = klappt). */
    val failedRefreshes: Int = 0,
    val refreshError: FeedError? = null,
    val failingSince: Long = 0,
    /** Seite zum Unterstützen (`podcast:funding`) und ihr Text aus dem Feed (kann leer sein). */
    val fundingUrl: String? = null,
    val fundingLabel: String = "",
) {
    /** Aus dem Ordner „Podcasts“: keine Feed-Adresse, kein Abgleich, kein Download, kein Abo zu beenden. */
    val isLocal: Boolean get() = id.startsWith(LOCAL_PODCAST_PREFIX)

    /** Scheitert der Abgleich seit [FEED_PROBLEM_AFTER] Versuchen, zeigt die App es an (Konzept 12.10). */
    val hasProblem: Boolean get() = failedRefreshes >= FEED_PROBLEM_AFTER
}

/** Ab so vielen fehlgeschlagenen Abgleichen in Folge gilt ein Abo als kaputt; ein einzelner Ausfall ist normal. */
const val FEED_PROBLEM_AFTER = 3

/** Warum der Abgleich eines Feeds scheiterte. */
enum class FeedError {
    /** 404/410: Den Feed gibt es unter der Adresse nicht mehr. */
    GONE,

    /** 401/403: Der Server verlangt eine Anmeldung oder verweigert den Abruf. */
    DENIED,

    /** Andere HTTP-Fehler (meist 5xx). */
    SERVER,

    /** Die Antwort ist kein RSS- oder Atom-Feed. */
    NOT_A_FEED,

    /** Zertifikat oder TLS-Verbindung abgelehnt. */
    TLS,

    /** Server nicht erreichbar (Name unbekannt, Zeitüberschreitung, Verbindung abgelehnt). */
    UNREACHABLE,
}

/** Stelle in einer Datei des Buchs. */
data class TrackPosition(val trackIndex: Int, val offsetMs: Long)

/**
 * Rechnet zwischen der Position im ganzen Buch und (Datei, Position in der Datei) um und liefert Kapitel und
 * Restzeiten. Die UI zeigt nur Buchpositionen (Konzept 12.2).
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
     * Beginn des Kapitels, zu dem „zurück“ springt: Ist man schon mehr als [restartThresholdMs] im Kapitel,
     * wird es neu gestartet, sonst geht es ins vorherige.
     */
    fun previousChapterStart(positionMs: Long, restartThresholdMs: Long = 3_000): Long? {
        val current = chapterAt(positionMs) ?: return null
        if (positionMs - current.startMs > restartThresholdMs) return current.startMs
        return chapters.lastOrNull { it.startMs < current.startMs }?.startMs ?: current.startMs
    }

    /** Restzeit des Buchs in Wiedergabezeit, also durch die Geschwindigkeit geteilt. */
    fun remainingBookMs(positionMs: Long, speed: Float = 1f): Long =
        ((durationMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(MIN_SPEED)).toLong()

    /** Restzeit im aktuellen Kapitel; `null`, wenn das Buch keine Kapitel hat. */
    fun remainingChapterMs(positionMs: Long, speed: Float = 1f): Long? {
        val chapter = chapterAt(positionMs) ?: return null
        return ((chapter.endMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(MIN_SPEED)).toLong()
    }

    private companion object {
        const val MIN_SPEED = 0.1f
    }
}
