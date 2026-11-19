package de.kleinabersmart.earloft.core.playback

/**
 * Measures listened **content time**: time during which playback runs, times speed (at 2× one minute of wall clock counts
 * as two minutes of content, so one hour of content is always one hour). Pauses and seeking don't count because only the duration
 * between [start] and [stop] is used, not the position. Not thread-safe; the caller is the player thread.
 */
internal class ListeningMeter(private val clock: () -> Long = System::currentTimeMillis) {
    private var running = false
    private var since = 0L
    private var speed = 1f
    private var pending = 0.0

    val isRunning: Boolean get() = running

    fun start(speed: Float) {
        if (running) return
        running = true
        since = clock()
        this.speed = speed
    }

    /** A new speed applies from now on; the time before still counts with the old one. */
    fun setSpeed(speed: Float) {
        if (running) fold()
        this.speed = speed
    }

    fun stop() {
        if (running) fold()
        running = false
    }

    /** Content that passed without playback (skipped silence): counts as listened, otherwise an hour of content would be less. */
    fun addContent(ms: Long) {
        if (ms > 0) pending += ms
    }

    /** The time measured since the last call in ms; measuring continues. The remainder below 1 ms is kept. */
    fun drain(): Long {
        if (running) fold()
        val whole = pending.toLong()
        pending -= whole
        return whole
    }

    private fun fold() {
        val now = clock()
        pending += (now - since).coerceAtLeast(0) * speed
        since = now
    }
}
