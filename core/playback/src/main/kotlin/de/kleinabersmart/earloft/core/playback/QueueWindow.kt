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
 * At most [maxSize] entries from [items], always including the start title. In order: the start title and
 * the following ones. With shuffle: the start title and a random selection from the rest (in the
 * original order), so that "shuffle all" doesn't just shuffle one section of the list.
 */
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

fun <T> queueWindow(items: List<T>, startIndex: Int, maxSize: Int, shuffle: Boolean, random: Random = Random): QueueWindow<T> {
    if (items.size <= maxSize) return QueueWindow(items, startIndex)
    val start = startIndex.coerceIn(items.indices)
    if (!shuffle) {
        val end = minOf(start + maxSize, items.size)
        return QueueWindow(items.subList(start, end), 0, before = items.subList(0, start), after = items.subList(end, items.size))
    }
    val others = (items.indices - start).shuffled(random).take(maxSize - 1)
    val chosen = (others + start).sorted()
    val chosenSet = chosen.toSet()
    return QueueWindow(
        items = chosen.map(items::get),
        startIndex = chosen.indexOf(start),
        after = items.filterIndexed { index, _ -> index !in chosenSet },
    )
}
