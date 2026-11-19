package de.kleinabersmart.earloft.core.playback

/** This close to the end a playing book or episode counts as finished (credits). */
internal const val FINISHED_TOLERANCE_MS = 5_000L

/** Stopped episodes: at least this much may be missing … */
internal const val EPISODE_STOPPED_MIN_MS = 30_000L

/**
 * Whether [positionMs] counts as finished (concept 12.3). When stopped ([stopped]: pause, stop, switch to something else)
 * it counts as played already shortly before the end – hardly anyone listens to credits and ads at the end:
 * - Episodes: max(30 s, 2 %) may be missing, for very short episodes at most 10 %.
 * - Books: from 99 %, but with chapters only in the last chapter ([lastChapterStartMs]) – otherwise a short final chapter
 *   would take content of the second to last one with it.
 *
 * While playing, [FINISHED_TOLERANCE_MS] applies; otherwise cleanup and trial subscriptions would take an episode's file while
 * it is still playing, and a book would drop out of "Continue listening" mid-listen.
 * Without a known duration (episode whose length hasn't been learned yet) nothing is finished.
 */
internal fun isFinishedAt(
    positionMs: Long,
    durationMs: Long,
    isEpisode: Boolean,
    stopped: Boolean,
    lastChapterStartMs: Long = 0L,
): Boolean {
    if (durationMs <= 0) return false
    if (positionMs >= durationMs - FINISHED_TOLERANCE_MS) return true
    if (!stopped) return false
    return if (isEpisode) {
        val tolerance = maxOf(EPISODE_STOPPED_MIN_MS, durationMs / 50).coerceAtMost(durationMs / 10)
        positionMs >= durationMs - tolerance
    } else {
        positionMs >= durationMs - durationMs / 100 && positionMs >= lastChapterStartMs
    }
}
