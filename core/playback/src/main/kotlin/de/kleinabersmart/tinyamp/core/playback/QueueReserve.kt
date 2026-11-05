package de.kleinabersmart.tinyamp.core.playback

/**
 * Der Teil einer Warteschlange, der gerade nicht im Player liegt (Cast: eine Nachricht fasst nur etwa 50 Titel).
 * [after] wird stückweise nachgereicht ([take]), wenn der Player sich dem Ende nähert; [before] (was vor dem
 * Starttitel lag) und der Rest kommen beim Wechsel zurück aufs Handy wieder dazu ([complete]). Nur Main-Thread.
 */
class QueueReserve<T> {

    private var before: List<T> = emptyList()
    private val after = ArrayDeque<T>()

    /** Wie viele Titel noch nachgereicht werden können. */
    val remaining: Int get() = after.size

    /** Weder etwas davor noch etwas dahinter. */
    fun isEmpty(): Boolean = before.isEmpty() && after.isEmpty()

    fun reset(before: List<T> = emptyList(), after: List<T> = emptyList()) {
        this.before = before
        this.after.clear()
        this.after.addAll(after)
    }

    fun clear() = reset()

    /** Hängt [items] hinter alles an, was noch aussteht. */
    fun append(items: List<T>) {
        after.addAll(items)
    }

    /** Die nächsten höchstens [count] Titel, ohne sie zu entnehmen. */
    fun peek(count: Int): List<T> = after.take(count)

    /** Entnimmt die nächsten höchstens [count] Titel. */
    fun take(count: Int): List<T> {
        val taken = ArrayList<T>(minOf(count, after.size))
        while (taken.size < count && after.isNotEmpty()) taken.add(after.removeFirst())
        return taken
    }

    /** Die vollständige Liste um [window] herum und der Index von [windowIndex] darin. */
    fun complete(window: List<T>, windowIndex: Int): Pair<List<T>, Int> {
        val index = if (windowIndex < 0) windowIndex else windowIndex + before.size
        return (before + window + after) to index
    }
}
