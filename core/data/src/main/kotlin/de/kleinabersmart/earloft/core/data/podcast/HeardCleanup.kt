package de.kleinabersmart.earloft.core.data.podcast

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Eine geladene, als gehört markierte Folge: wann sie zu Ende gehört und wann ihre Datei fertig wurde. */
data class HeardDownload(val bookId: Long, val finishedAt: Long, val downloadedAt: Long)

/**
 * So lange nach „Gehört“ wartet „Gehörte löschen: sofort“: Bis dahin kann „Rückgängig“ in der Snackbar die Folge zurückholen, und
 * ihr Download soll dann noch da sein (Review D22). Länger als eine lange Snackbar, auch mit verlängerter Zeit für Bedienungshilfen.
 */
const val HEARD_UNDO_GRACE_MS = 30_000L

/**
 * Folgen, deren Download gelöscht werden soll: **nach** dem Download zu Ende gehört und das vor mindestens [afterDays]
 * Tagen. 0 heißt sofort (nach [HEARD_UNDO_GRACE_MS]), ein negativer Wert ([PodcastSettings.DELETE_HEARD_NEVER]) nie. Wer
 * eine schon gehörte Folge von Hand lädt, behält sie, bis er sie wieder zu Ende gehört hat.
 */
fun expiredHeardDownloads(heard: List<HeardDownload>, afterDays: Int, now: Long): List<Long> {
    if (afterDays < 0) return emptyList()
    val after = maxOf(afterDays * DAY_MS, HEARD_UNDO_GRACE_MS)
    return heard
        .filter { it.finishedAt > it.downloadedAt && now - it.finishedAt >= after }
        .map { it.bookId }
}
