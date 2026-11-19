package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookProgress

/**
 * How resuming an episode or book kicks in: if the pause was longer than [afterSeconds], playback continues
 * [rewindSeconds] earlier. [rewindSeconds] = 0 disables rewinding.
 */
data class ResumeRule(val afterSeconds: Int, val rewindSeconds: Int) {
    companion object {
        /** Options in seconds for the pause after which playback rewinds. */
        val AFTER_CHOICES = listOf(30, 60, 120, 300, 600, 900, 1800, 3600)

        /** Options in seconds for rewinding; 0 = off. */
        val REWIND_CHOICES = listOf(0, 5, 10, 15, 30, 60)

        const val DEFAULT_AFTER_S = 300
        const val DEFAULT_REWIND_S = 10
    }
}

/**
 * Position at which playback resumes. [BookProgress.updatedAt] is the time of the last listening
 * (the player writes it on pause, at the end and every 15 s). A finished item starts from the beginning.
 *
 * With [restartChapter] a chapter starts from the beginning if playback was stopped less than [CHAPTER_RESTART_WINDOW_MS] after
 * its start – regardless of the length of the pause.
 */
fun resumePosition(
    progress: BookProgress?,
    nowMs: Long,
    rule: ResumeRule,
    chapters: List<BookChapter> = emptyList(),
    restartChapter: Boolean = false,
): Long {
    if (progress == null || progress.finished) return 0L
    val position = progress.positionMs
    if (restartChapter) {
        val chapter = chapters.lastOrNull { it.startMs <= position }
        if (chapter != null && position - chapter.startMs < CHAPTER_RESTART_WINDOW_MS) return chapter.startMs
    }
    val pausedMs = nowMs - progress.updatedAt
    return if (rule.rewindSeconds > 0 && pausedMs > rule.afterSeconds * 1000L) {
        (position - rule.rewindSeconds * 1000L).coerceAtLeast(0)
    } else {
        position
    }
}

const val CHAPTER_RESTART_WINDOW_MS = 30_000L
