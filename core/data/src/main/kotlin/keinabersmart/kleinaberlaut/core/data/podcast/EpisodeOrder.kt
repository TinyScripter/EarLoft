package keinabersmart.kleinaberlaut.core.data.podcast

/**
 * Die Folge, die in der Abspielreihenfolge [playOrder] auf [currentId] folgt und noch nicht zu Ende gehört ist.
 * Ohne [currentId] (z. B. „Alle abspielen“) ist es die erste ungehörte Folge, und sind alle gehört, die erste überhaupt.
 * `null`, wenn nach [currentId] nichts Ungehörtes mehr kommt.
 */
internal fun nextEpisode(playOrder: List<Long>, finished: Set<Long>, currentId: Long? = null): Long? {
    if (currentId == null) return playOrder.firstOrNull { it !in finished } ?: playOrder.firstOrNull()
    val index = playOrder.indexOf(currentId)
    if (index < 0) return null
    return playOrder.drop(index + 1).firstOrNull { it !in finished }
}
