package de.kleinabersmart.earloft.core.playback

/**
 * The part of a queue that is currently not in the player (cast: the receiver only holds about 50 titles).
 * [after] is handed over piece by piece ([take]) as the player approaches the end; in return played titles move from the
 * player to [before] ([keepBefore]). [before] (what lay before the window) and the rest are added back when switching back
 * to the phone ([complete]). Main thread only.
 */
class QueueReserve<T> {

    private var before: List<T> = emptyList()
    private val after = ArrayDeque<T>()

    /** How many titles can still be handed over. */
    val remaining: Int get() = after.size

    /** Neither anything before nor anything after. */
    fun isEmpty(): Boolean = before.isEmpty() && after.isEmpty()

    fun reset(before: List<T> = emptyList(), after: List<T> = emptyList()) {
        this.before = before
        this.after.clear()
        this.after.addAll(after)
    }

    fun clear() = reset()

    /** Whatever still was to be handed over is dropped; what lay before the window stays. */
    fun clearAfter() = after.clear()

    /** Played titles removed from the player to make room: they belong after the existing [before]. */
    fun keepBefore(items: List<T>) {
        before = before + items
    }

    /** Appends [items] after everything still pending. */
    fun append(items: List<T>) {
        after.addAll(items)
    }

    /** The next at most [count] titles, without taking them. */
    fun peek(count: Int): List<T> = after.take(count)

    /** Takes the next at most [count] titles. */
    fun take(count: Int): List<T> {
        val taken = ArrayList<T>(minOf(count, after.size))
        while (taken.size < count && after.isNotEmpty()) taken.add(after.removeFirst())
        return taken
    }

    /** The complete list around [window] and the index of [windowIndex] in it. */
    fun complete(window: List<T>, windowIndex: Int): Pair<List<T>, Int> {
        val index = if (windowIndex < 0) windowIndex else windowIndex + before.size
        return (before + window + after) to index
    }
}
