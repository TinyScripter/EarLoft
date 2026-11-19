package de.kleinabersmart.earloft.core.data.podcast

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** A downloaded episode marked as played: when it was played to the end and when its file was finished. */
data class HeardDownload(val bookId: Long, val finishedAt: Long, val downloadedAt: Long)

/**
 * How long "Delete played: immediately" waits after "Played": until then, "Undo" in the snackbar can bring the episode back, and
 * its download should still be there (review D22). Longer than a long snackbar, even with extended time for accessibility.
 */
const val HEARD_UNDO_GRACE_MS = 30_000L

/**
 * Episodes whose download should be deleted: played to the end **after** the download, and that at least [afterDays]
 * days ago. 0 means immediately (after [HEARD_UNDO_GRACE_MS]), a negative value ([PodcastSettings.DELETE_HEARD_NEVER]) never. Anyone who
 * downloads an already played episode by hand keeps it until they have played it to the end again.
 */
fun expiredHeardDownloads(heard: List<HeardDownload>, afterDays: Int, now: Long): List<Long> {
    if (afterDays < 0) return emptyList()
    val after = maxOf(afterDays * DAY_MS, HEARD_UNDO_GRACE_MS)
    return heard
        .filter { it.finishedAt > it.downloadedAt && now - it.finishedAt >= after }
        .map { it.bookId }
}
