package de.kleinabersmart.tinyamp.core.data.spoken

import de.kleinabersmart.tinyamp.core.model.BookChapter
import de.kleinabersmart.tinyamp.core.model.BookProgress

/**
 * Wie das Fortsetzen einer Folge bzw. eines Buchs einsetzt: War die Pause länger als [afterSeconds], geht es
 * [rewindSeconds] früher weiter. [rewindSeconds] = 0 schaltet das Zurückspulen aus.
 */
data class ResumeRule(val afterSeconds: Int, val rewindSeconds: Int) {
    companion object {
        /** Wahlmöglichkeiten in Sekunden für die Pause, ab der zurückgespult wird. */
        val AFTER_CHOICES = listOf(30, 60, 120, 300, 600, 900, 1800, 3600)

        /** Wahlmöglichkeiten in Sekunden für das Zurückspulen; 0 = aus. */
        val REWIND_CHOICES = listOf(0, 5, 10, 15, 30, 60)

        const val DEFAULT_AFTER_S = 300
        const val DEFAULT_REWIND_S = 10
    }
}

/**
 * Position, an der die Wiedergabe fortgesetzt wird. [BookProgress.updatedAt] ist der Zeitpunkt, an dem zuletzt gehört
 * wurde (der Player schreibt ihn bei Pause, Ende und alle 15 s). Ein beendetes Stück beginnt von vorn.
 *
 * Mit [restartChapter] beginnt ein Kapitel von vorn, wenn die Wiedergabe weniger als [CHAPTER_RESTART_WINDOW_MS] nach
 * dessen Anfang beendet wurde – unabhängig von der Länge der Pause.
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
