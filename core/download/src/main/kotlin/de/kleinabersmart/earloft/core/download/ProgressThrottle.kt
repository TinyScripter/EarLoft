package de.kleinabersmart.earloft.core.download

import android.os.SystemClock

/**
 * Throttles the progress notification of the download workers: every update rebuilds the notification and queries the
 * number of waiting downloads. With fast downloads the percentage would otherwise change several times per second.
 */
class ProgressThrottle(
    private val intervalMs: Long = INTERVAL_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastPercent = UNKNOWN
    private var lastAt = 0L
    private var reported = false

    /**
     * @return the progress to report in percent ([UNKNOWN] without a known size) if it has changed and the
     * last report was at least [intervalMs] ago; otherwise `null`. The first change is reported immediately.
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
