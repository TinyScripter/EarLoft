package de.kleinabersmart.earloft.core.model

/**
 * Welche Folgen die Seite eines Abos zeigt (je Abo gespeichert). [UNHEARD]: nicht zu Ende gehört, angefangene inbegriffen;
 * [STARRED]: gemerkte.
 */
enum class EpisodeFilter { ALL, UNHEARD, STARTED, DOWNLOADED, STARRED }

/**
 * Was von selbst in „Weiterhören“ kommt (je Abo): [ALL_NEW] die neuen Folgen der letzten Tage, [NEWEST_ONLY] nur die neueste
 * noch nicht angefangene (Nachrichten, Tagespodcasts; die Download-Automatik lädt dann auch nur sie), [MANUAL] nichts – nur
 * was man selbst startet oder einreiht. Angefangene Folgen stehen immer dort.
 */
enum class FeedQueueMode { ALL_NEW, NEWEST_ONLY, MANUAL }

/** Reihenfolge der Abos auf Start und in der Bibliothek. */
enum class FeedSort { NAME, NEWEST_EPISODE, RECENTLY_PLAYED, MOST_NEW }

/**
 * Kennzahlen eines Abos für Sortierung und Kachel; Zeiten in Unix-ms, 0 = keine. [newEpisodes]: nie angefangene Folgen, die
 * neuer sind als die zuletzt angefangene oder gehörte – der Rückstand dahinter zählt nicht (bei Archiven sonst Hunderte).
 */
data class FeedStats(val newestEpisodeAt: Long = 0, val lastPlayedAt: Long = 0, val newEpisodes: Int = 0)

/** Nicht zu Ende gehört. */
val BookProgress?.isUnheard: Boolean get() = this?.finished != true

/** Angefangen und nicht zu Ende gehört. */
val BookProgress?.isStarted: Boolean get() = this != null && !finished && positionMs > 0

/**
 * „Folgen behalten: die neuesten n“: Von den Folgen (neueste zuerst, auch mehrerer Abos gemischt) entfallen je Abo ([group])
 * die älteren als die neuesten [keep] (0 = alle), sofern sie nie angefangen wurden ([untouched]). Angefangene und gehörte
 * bleiben, ebenso alles im Feed – es geht nur um die Anzeige.
 */
fun <T> List<T>.keepNewest(group: (T) -> String, keep: (group: String) -> Int, untouched: (T) -> Boolean): List<T> {
    val seen = HashMap<String, Int>()
    return filter { item ->
        val key = group(item)
        val index = seen.getOrDefault(key, 0)
        seen[key] = index + 1
        val limit = keep(key)
        limit <= 0 || index < limit || !untouched(item)
    }
}

/** Die Folgen eines Abos (neueste zuerst) nach [filter], mit [oldestFirst] die älteste zuerst. */
fun List<BookWithProgress>.filterEpisodes(filter: EpisodeFilter, oldestFirst: Boolean, downloaded: Set<Long>): List<BookWithProgress> {
    val shown = when (filter) {
        EpisodeFilter.ALL -> this
        EpisodeFilter.UNHEARD -> filter { it.progress.isUnheard }
        EpisodeFilter.STARTED -> filter { it.progress.isStarted }
        EpisodeFilter.DOWNLOADED -> filter { it.book.id in downloaded }
        EpisodeFilter.STARRED -> filter { it.book.starred }
    }
    return if (oldestFirst) shown.asReversed() else shown
}

/** Abos (nach Name) in der Reihenfolge [sort]; bei Gleichstand bleibt die nach Name. */
fun List<PodcastFeed>.sortedFeeds(sort: FeedSort, stats: Map<String, FeedStats>): List<PodcastFeed> = when (sort) {
    FeedSort.NAME -> this
    FeedSort.NEWEST_EPISODE -> sortedByDescending { stats[it.id]?.newestEpisodeAt ?: 0 }
    FeedSort.RECENTLY_PLAYED -> sortedByDescending { stats[it.id]?.lastPlayedAt ?: 0 }
    FeedSort.MOST_NEW -> sortedByDescending { stats[it.id]?.newEpisodes ?: 0 }
}
