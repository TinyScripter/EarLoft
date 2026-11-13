package de.kleinabersmart.earloft.core.playback

/** So kurz vor dem Ende gilt ein Buch bzw. eine laufende Folge als zu Ende gehört (Abspann). */
internal const val FINISHED_TOLERANCE_MS = 5_000L

/** Angehaltene Folgen: mindestens so viel darf fehlen … */
internal const val EPISODE_STOPPED_MIN_MS = 30_000L

/**
 * Ob an [positionMs] zu Ende gehört ist (Konzept 12.3). Eine angehaltene Folge ([stopped]: Pause, Stopp, Wechsel zu
 * etwas anderem) gilt schon kurz vor dem Ende als gehört – Abspann und Werbung am Schluss hört kaum jemand: es dürfen
 * max(30 s, 2 %) fehlen, bei sehr kurzen Folgen höchstens 10 %. Solange sie läuft, gilt wie bei Büchern
 * [FINISHED_TOLERANCE_MS]; sonst nähmen Aufräumen und Probeabos ihr die Datei, während sie noch spielt.
 * Ohne bekannte Dauer (Folge, deren Länge noch nicht gelernt ist) ist nichts zu Ende.
 */
internal fun isFinishedAt(positionMs: Long, durationMs: Long, isEpisode: Boolean, stopped: Boolean): Boolean {
    if (durationMs <= 0) return false
    val tolerance = if (isEpisode && stopped) {
        maxOf(EPISODE_STOPPED_MIN_MS, durationMs / 50).coerceAtMost(durationMs / 10).coerceAtLeast(FINISHED_TOLERANCE_MS)
    } else {
        FINISHED_TOLERANCE_MS
    }
    return positionMs >= durationMs - tolerance
}
