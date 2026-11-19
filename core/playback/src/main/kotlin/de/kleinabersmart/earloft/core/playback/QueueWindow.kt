package de.kleinabersmart.earloft.core.playback

import kotlin.random.Random

/**
 * A window of a queue; [startIndex] refers to [items]. [before] and [after] are the rest of the
 * list before and after the window (with shuffle everything not chosen is in [after]).
 */
data class QueueWindow<T>(
    val items: List<T>,
    val startIndex: Int,
    val before: List<T> = emptyList(),
    val after: List<T> = emptyList(),
)

/**
 * Which titles may leave the player to make room: at most [count] already played ones, the oldest first.
 * [previous] returns the title before an index in playback order (with shuffle the shuffled one), `-1` at the start
 * (`Timeline.getPreviousWindowIndex`). Result sorted ascending.
 */
fun oldestPlayed(current: Int, count: Int, previous: (Int) -> Int): List<Int> {
    if (current < 0 || count <= 0) return emptyList()
    val played = generateSequence(previous(current).takeIf { it >= 0 }) { index -> previous(index).takeIf { it >= 0 } }.toList()
    return played.takeLast(count).sorted()
}

/**
 * At most [maxSize] entries from [items], always including the start title. In order: the start title and
 * the following ones; a short list stays untouched. With shuffle (for a player that knows none and only plays forward,
 * the cast receiver): the start title first, then all others in random order, also in a short list; whatever doesn't fit
 * follows in [QueueWindow.after] in the same random order. So "shuffle all" covers the whole list and no title lies before the start.
 */
fun <T> queueWindow(items: List<T>, startIndex: Int, maxSize: Int, shuffle: Boolean, random: Random = Random): QueueWindow<T> {
    if (items.isEmpty() || (!shuffle && items.size <= maxSize)) return QueueWindow(items, startIndex)
    val start = startIndex.coerceIn(items.indices)
    if (!shuffle) {
        val end = minOf(start + maxSize, items.size)
        return QueueWindow(items.subList(start, end), 0, before = items.subList(0, start), after = items.subList(end, items.size))
    }
    val order = listOf(start) + (items.indices - start).shuffled(random)
    return QueueWindow(
        items = order.take(maxSize).map(items::get),
        startIndex = 0,
        after = order.drop(maxSize).map(items::get),
    )
}

/**
 * [items] with the current one at [currentIndex] put back into the list order of [original] (after casting with shuffle,
 * [queueWindow]); titles not in [original] follow in their order. A title that occurs more than once is matched in turn.
 * @return the list and the new index of the current title (negative stays as it is).
 */
fun <T> restoreOrder(items: List<T>, currentIndex: Int, original: List<T>, keyOf: (T) -> Any?): Pair<List<T>, Int> {
    val positions = HashMap<Any?, ArrayDeque<Int>>()
    original.forEachIndexed { index, item -> positions.getOrPut(keyOf(item)) { ArrayDeque() }.addLast(index) }
    val rank = items.map { positions[keyOf(it)]?.removeFirstOrNull() ?: Int.MAX_VALUE }
    val order = items.indices.sortedWith(compareBy({ rank[it] }, { it }))
    return order.map(items::get) to if (currentIndex < 0) currentIndex else order.indexOf(currentIndex)
}
