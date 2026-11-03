package keinabersmart.kleinaberlaut.core.download

import android.os.SystemClock

/**
 * Drosselt die Fortschritts-Benachrichtigung der Download-Worker: Jede Meldung baut die Benachrichtigung neu und fragt die
 * Zahl der wartenden Downloads ab. Bei schnellen Downloads wechselt das Prozent sonst mehrmals pro Sekunde.
 */
class ProgressThrottle(
    private val intervalMs: Long = INTERVAL_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastPercent = UNKNOWN
    private var lastAt = 0L
    private var reported = false

    /**
     * @return der zu meldende Fortschritt in Prozent ([UNKNOWN] ohne bekannte Größe), wenn er sich geändert hat und die
     * letzte Meldung mindestens [intervalMs] zurückliegt; sonst `null`. Die erste Änderung wird sofort gemeldet.
     */
    fun next(done: Long, total: Long?): Int? {
        val percent = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt() } ?: UNKNOWN
        if (percent == lastPercent) return null
        val now = clock()
        if (reported && now - lastAt < intervalMs) return null
        lastPercent = percent
        lastAt = now
        reported = true
        return percent
    }

    companion object {
        const val UNKNOWN = -1
        private const val INTERVAL_MS = 1_000L
    }
}
