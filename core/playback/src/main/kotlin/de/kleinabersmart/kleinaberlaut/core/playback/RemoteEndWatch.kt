package de.kleinabersmart.kleinaberlaut.core.playback

/**
 * Cast meldet das Ende nicht als `STATE_ENDED`: Der Empfänger geht in den Leerlauf (`STATE_IDLE`), entlädt die Datei,
 * und danach liest der Player nur noch Position 0. Die Wache merkt sich deshalb, wo das Buch beim letzten Blick lief,
 * und rechnet beim Stillstand hoch, wie weit es inzwischen sein müsste. Steht der Empfänger mitten im Buch still
 * (Sitzung beendet, von anderswo gestoppt), ist es nicht zu Ende. Nicht threadsicher; der Aufrufer ist der Player-Thread.
 */
internal class RemoteEndWatch(private val clock: () -> Long = System::currentTimeMillis) {

    /** Das Buch, das beim Stillstand zu Ende gelaufen ist. */
    data class Ended(val bookId: Long, val isEpisode: Boolean, val durationMs: Long)

    private data class Sample(
        val bookId: Long,
        val isEpisode: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
        val at: Long,
    )

    private var sample: Sample? = null

    /** Es läuft gerade [bookId] an [positionMs] (im ganzen Buch) von [durationMs] mit [speed]. */
    fun playing(bookId: Long, isEpisode: Boolean, positionMs: Long, durationMs: Long, speed: Float) {
        sample = Sample(bookId, isEpisode, positionMs, durationMs, speed, clock())
    }

    /** Die Wiedergabe steht (Pause, anderes Buch): Die bisherige Beobachtung gilt nicht mehr. */
    fun reset() {
        sample = null
    }

    /** Der Empfänger ist von selbst stehen geblieben: das Buch, das dabei zu Ende gelaufen ist, sonst `null`. */
    fun ended(): Ended? {
        val last = sample ?: return null
        sample = null
        if (last.durationMs <= 0) return null
        val estimated = last.positionMs + ((clock() - last.at).coerceAtLeast(0) * last.speed).toLong()
        return Ended(last.bookId, last.isEpisode, last.durationMs).takeIf { estimated >= last.durationMs - END_SLACK_MS }
    }

    private companion object {
        /** So weit darf die hochgerechnete Stelle vor dem Ende liegen (Abspann, verzögerte Meldung des Empfängers). */
        const val END_SLACK_MS = 10_000L
    }
}
