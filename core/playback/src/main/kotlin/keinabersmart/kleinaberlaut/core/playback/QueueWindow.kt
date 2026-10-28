package keinabersmart.kleinaberlaut.core.playback

import kotlin.random.Random

/** Ausschnitt einer Warteschlange; [startIndex] bezieht sich auf [items]. */
data class QueueWindow<T>(val items: List<T>, val startIndex: Int)

/**
 * Höchstens [maxSize] Einträge aus [items], immer mit dem Starttitel. Der Reihe nach: der Starttitel und
 * die folgenden. Bei Zufallswiedergabe: der Starttitel und eine zufällige Auswahl aus dem Rest (in der
 * ursprünglichen Reihenfolge), damit „alles zufällig“ nicht nur einen Abschnitt der Liste mischt.
 */
fun <T> queueWindow(items: List<T>, startIndex: Int, maxSize: Int, shuffle: Boolean, random: Random = Random): QueueWindow<T> {
    if (items.size <= maxSize) return QueueWindow(items, startIndex)
    val start = startIndex.coerceIn(items.indices)
    if (!shuffle) return QueueWindow(items.subList(start, minOf(start + maxSize, items.size)), 0)
    val others = (items.indices - start).shuffled(random).take(maxSize - 1)
    val chosen = (others + start).sorted()
    return QueueWindow(chosen.map(items::get), chosen.indexOf(start))
}
