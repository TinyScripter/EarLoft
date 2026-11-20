package de.kleinabersmart.earloft.core.playback

/**
 * The part of a queue that is currently not in the player (cast: the receiver only holds about 50 titles).
 * [after] is handed over piece by piece ([take]) as the player approaches the end; in return played titles move from the
 * player to [before] ([keepBefore]). [before] (what lay before the window) and the rest are added back when switching back
 * to the phone ([complete]). With "repeat all" the reserve is a ring ([nextRound]). Main thread only.
 * [keyOf] identifies a title across the player (the receiver reports its own copies of the items).
 */
class QueueReserve<T>(private val keyOf: (T) -> Any? = { it }) {

    private var before: List<T> = emptyList()
    private val after = ArrayDeque<T>()

    /** Key of the list's first title once [nextRound] has rotated the list; [complete] turns it back. */
    private var listStart: Any? = null
    private var rotated = false

    /** By how many titles [nextRound] has rotated the list in total; tells apart several titles with the key [listStart]. */
    private var rotatedBy = 0

    /** How many titles can still be handed over. */
    val remaining: Int get() = after.size

    /** Neither anything before nor anything after. */
    fun isEmpty(): Boolean = before.isEmpty() && after.isEmpty()

    /** Whether something lay before the window or was played and taken out of the player. */
    val hasBefore: Boolean get() = before.isNotEmpty()

    fun reset(before: List<T> = emptyList(), after: List<T> = emptyList()) {
        this.before = before
        this.after.clear()
        this.after.addAll(after)
        listStart = null
        rotated = false
        rotatedBy = 0
    }

    /**
     * "Repeat all" and nothing left to hand over: what lay before the window (and what was played and taken out) becomes the next
     * round, so after the last title the receiver continues with the list's first instead of the oldest one it still holds.
     * @return whether there is something to hand over now.
     */
    fun nextRound(): Boolean {
        if (after.isNotEmpty() || before.isEmpty()) return false
        if (!rotated) {
            listStart = keyOf(before.first())
            rotated = true
        }
        rotatedBy += before.size
        after.addAll(before)
        before = emptyList()
        return true
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
        val all = before + window + after
        // After [nextRound] the list is rotated; it goes back to the phone in its original order.
        // If the first title occurs more than once, the one where the rotation puts the start (review G41); titles added since
        // shift it only a little, so the nearest occurrence counts.
        val matches = if (rotated) all.indices.filter { keyOf(all[it]) == listStart } else emptyList()
        if (matches.isEmpty()) return all to index
        val expected = (all.size - rotatedBy % all.size) % all.size
        val start = matches.minBy { minOf(Math.floorMod(it - expected, all.size), Math.floorMod(expected - it, all.size)) }
        if (start == 0) return all to index
        val unrotated = all.subList(start, all.size) + all.subList(0, start)
        return unrotated to if (index < 0) index else (index - start + all.size) % all.size
    }
}
