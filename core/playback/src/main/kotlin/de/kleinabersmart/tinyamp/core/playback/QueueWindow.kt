package de.kleinabersmart.tinyamp.core.playback

import kotlin.random.Random

/**
 * Ausschnitt einer Warteschlange; [startIndex] bezieht sich auf [items]. [before] und [after] sind der Rest der
 * Liste vor bzw. hinter dem Ausschnitt (bei Zufallswiedergabe liegt alles Nichtgewählte in [after]).
 */
data class QueueWindow<T>(
    val items: List<T>,
    val startIndex: Int,
    val before: List<T> = emptyList(),
    val after: List<T> = emptyList(),
)

/**
 * Höchstens [maxSize] Einträge aus [items], immer mit dem Starttitel. Der Reihe nach: der Starttitel und
 * die folgenden. Bei Zufallswiedergabe: der Starttitel und eine zufällige Auswahl aus dem Rest (in der
 * ursprünglichen Reihenfolge), damit „alles zufällig“ nicht nur einen Abschnitt der Liste mischt.
 */
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
