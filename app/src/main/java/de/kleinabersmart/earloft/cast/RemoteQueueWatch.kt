package de.kleinabersmart.earloft.cast

/**
 * Has the receiver discarded a queue sent to it during the session? After sending, the player's
 * timeline is empty until the receiver reports (for Audiobookshelf books up to about 9 s on the device).
 * If it can't reach the server, it accepts the titles, runs through them fruitlessly within a few seconds and ends up
 * without titles (seen on the device). Discarded therefore means: first empty, then titles of the new queue,
 * then empty again ([onItems]) – or never any titles until the deadline ends ([onTimeout]).
 *
 * Titles only count if they belong to the new queue and the player was empty in between; reloading
 * the same titles (or the old queue, still briefly visible) thus triggers nothing. Main thread only.
 */
internal class RemoteQueueWatch {

    private var expected: Set<String>? = null
    private var wasEmpty = false
    private var sawItems = false

    val isWatching: Boolean get() = expected != null

    /** A queue with these media IDs went out. */
    fun start(mediaIds: Collection<String>) {
        expected = mediaIds.toSet()
        wasEmpty = false
        sawItems = false
    }

    fun stop() {
        expected = null
    }

    /** The timeline has changed; `true`: the receiver has discarded the queue. */
    fun onItems(mediaIds: List<String>): Boolean {
        val expected = expected ?: return false
        when {
            mediaIds.isEmpty() -> {
                if (sawItems) {
                    stop()
                    return true
                }
                wasEmpty = true
            }
            wasEmpty && mediaIds.any { it in expected } -> sawItems = true
        }
        return false
    }

    /** End of the deadline; `true`: no titles ever arrived and there are none. */
    fun onTimeout(itemCount: Int): Boolean {
        if (!isWatching) return false
        val failed = !sawItems && itemCount == 0
        stop()
        return failed
    }
}
