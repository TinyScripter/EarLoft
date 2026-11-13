package de.kleinabersmart.earloft.core.playback

/** So kurz vor dem Ende gilt ein laufendes Buch bzw. eine laufende Folge als zu Ende gehört (Abspann). */
internal const val FINISHED_TOLERANCE_MS = 5_000L

/** Angehaltene Folgen: mindestens so viel darf fehlen … */
internal const val EPISODE_STOPPED_MIN_MS = 30_000L

/**
 * Ob an [positionMs] zu Ende gehört ist (Konzept 12.3). Angehalten ([stopped]: Pause, Stopp, Wechsel zu etwas anderem)
 * gilt schon kurz vor dem Ende als gehört – Abspann und Werbung am Schluss hört kaum jemand:
 * - Folgen: es dürfen max(30 s, 2 %) fehlen, bei sehr kurzen Folgen höchstens 10 %.
 * - Bücher: ab 99 %, mit Kapiteln aber erst im letzten Kapitel ([lastChapterStartMs]) – ein kurzes Schlusskapitel
 *   nimmt sonst Inhalt des vorletzten mit.
 *
 * Solange es läuft, gilt [FINISHED_TOLERANCE_MS]; sonst nähmen Aufräumen und Probeabos einer Folge die Datei, während
 * sie noch spielt, und ein Buch fiele mitten im Hören aus „Weiterhören“.
 * Ohne bekannte Dauer (Folge, deren Länge noch nicht gelernt ist) ist nichts zu Ende.
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
