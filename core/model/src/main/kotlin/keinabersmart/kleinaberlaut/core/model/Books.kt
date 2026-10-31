package keinabersmart.kleinaberlaut.core.model

/** Gesprochenes (Konzept 12): ein Hörbuch oder eine Podcast-Folge. */
enum class BookKind { BOOK, EPISODE }

/** Schalter in der oberen Leiste von Start und Bibliothek (Konzept 12.5). */
enum class SpokenMode { MUSIC, SPOKEN }

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
)

data class BookChapter(
    val index: Int,
    val title: String,
    /** Beginn und Ende als Position im ganzen Buch. */
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Eine Audiodatei des Buchs. Ein m4b ist ein Titel, ein MP3-Ordner hat viele. */
data class BookTrack(
    val index: Int,
    val remoteFileId: String,
    /** Beginn der Datei als Position im ganzen Buch. */
    val startMs: Long,
    val durationMs: Long,
    val mimeType: String? = null,
)

data class BookProgress(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val speed: Float = 1f,
    /** Zeitpunkt der letzten Änderung (Unix-ms) – die neuere Seite gewinnt (Konzept 12.3). */
    val updatedAt: Long,
    /** Noch nicht an den Server gemeldet. */
    val dirty: Boolean = false,
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
)

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
