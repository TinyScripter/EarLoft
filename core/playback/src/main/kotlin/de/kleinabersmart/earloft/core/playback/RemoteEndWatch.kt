package de.kleinabersmart.earloft.core.playback

/**
 * Cast doesn't report the end as `STATE_ENDED`: the receiver goes idle (`STATE_IDLE`), unloads the file,
 * and afterwards the player only reads position 0. The watchdog therefore remembers where the book was at the last look,
 * and on standstill extrapolates how far it should have got by now. If the receiver stops in the middle of the book
 * (session ended, stopped from elsewhere), it isn't finished. Not thread-safe; the caller is the player thread.
 */
internal class RemoteEndWatch(private val clock: () -> Long = System::currentTimeMillis) {

    /** The book that played to the end at the standstill. */
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

    /** [bookId] is currently playing at [positionMs] (across the whole book) of [durationMs] with [speed]. */
    fun playing(bookId: Long, isEpisode: Boolean, positionMs: Long, durationMs: Long, speed: Float) {
        sample = Sample(bookId, isEpisode, positionMs, durationMs, speed, clock())
    }

    /** Playback is stopped (pause, another book): the previous observation no longer applies. */
    fun reset() {
        sample = null
    }

    /** The receiver stopped on its own: the book that played to the end, otherwise `null`. */
    fun ended(): Ended? {
        val last = sample ?: return null
        sample = null
        if (last.durationMs <= 0) return null
        val estimated = last.positionMs + ((clock() - last.at).coerceAtLeast(0) * last.speed).toLong()
        return Ended(last.bookId, last.isEpisode, last.durationMs).takeIf { estimated >= last.durationMs - END_SLACK_MS }
    }

    private companion object {
        /** The extrapolated position may be this far before the end (credits, delayed report from the receiver). */
        const val END_SLACK_MS = 10_000L
    }
}
