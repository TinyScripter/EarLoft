package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.model.BookWithProgress

/**
 * The next unplayed episode of a subscription: the first one not played to the end in its playback order ([newestFirst] as
 * configured for the subscription). The episodes ([this]) are newest first, as from [PodcastRepository.episodes]. `null` if
 * all are played – unlike [nextEpisode] without a current episode, it doesn't start over then.
 */
fun List<BookWithProgress>.firstUnheard(newestFirst: Boolean): BookWithProgress? =
    (if (newestFirst) this else asReversed()).firstOrNull { it.progress?.finished != true }

/**
 * The episode that follows [currentId] in the playback order [playOrder] and has not been played to the end.
 * Without [currentId] (e.g. "Play all") it is the first unplayed episode, and if all are played, the very first one.
 * `null` if nothing unplayed comes after [currentId].
 */
internal fun nextEpisode(playOrder: List<Long>, finished: Set<Long>, currentId: Long? = null): Long? {
    if (currentId == null) return playOrder.firstOrNull { it !in finished } ?: playOrder.firstOrNull()
    val index = playOrder.indexOf(currentId)
    if (index < 0) return null
    return playOrder.drop(index + 1).firstOrNull { it !in finished }
}
