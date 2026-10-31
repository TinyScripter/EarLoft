package keinabersmart.kleinaberlaut.core.data.podcast

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Eine geladene, als gehört markierte Folge: wann sie zu Ende gehört und wann ihre Datei fertig wurde. */
data class HeardDownload(val bookId: Long, val finishedAt: Long, val downloadedAt: Long)

/**
 * Folgen, deren Download gelöscht werden soll: **nach** dem Download zu Ende gehört und das vor mindestens [afterDays]
 * Tagen. 0 heißt sofort, ein negativer Wert ([PodcastSettings.DELETE_HEARD_NEVER]) nie. Wer eine schon gehörte Folge
 * von Hand lädt, behält sie, bis er sie wieder zu Ende gehört hat.
 */
fun expiredHeardDownloads(heard: List<HeardDownload>, afterDays: Int, now: Long): List<Long> {
    if (afterDays < 0) return emptyList()
    return heard
        .filter { it.finishedAt > it.downloadedAt && now - it.finishedAt >= afterDays * DAY_MS }
        .map { it.bookId }
}
