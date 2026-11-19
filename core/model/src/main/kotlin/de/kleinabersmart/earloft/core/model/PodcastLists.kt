package de.kleinabersmart.earloft.core.model

/**
 * Which episodes a subscription's page shows (stored per subscription). [UNHEARD]: not fully played, started ones included;
 * [STARRED]: bookmarked ones.
 */
enum class EpisodeFilter { ALL, UNHEARD, STARTED, DOWNLOADED, STARRED }

/**
 * What goes into "Continue listening" automatically (per subscription): [ALL_NEW] the new episodes of the last few days, [NEWEST_ONLY] only the newest
 * not yet started one (news, daily podcasts; the download automation then also only downloads that one), [MANUAL] nothing – only
 * what you start or enqueue yourself. Started episodes are always there.
 */
enum class FeedQueueMode { ALL_NEW, NEWEST_ONLY, MANUAL }

/** Sort order of the subscriptions on Home and in the library. */
enum class FeedSort { NAME, NEWEST_EPISODE, RECENTLY_PLAYED, MOST_NEW }

/**
 * Metrics of a subscription for sorting and tile; times in Unix ms, 0 = none. [newEpisodes]: never-started episodes that
 * are newer than the last started or played one – the backlog behind it doesn't count (otherwise hundreds for archives).
 */
data class FeedStats(val newestEpisodeAt: Long = 0, val lastPlayedAt: Long = 0, val newEpisodes: Int = 0)

/** Not fully played. */
val BookProgress?.isUnheard: Boolean get() = this?.finished != true

/** Started and not fully played. */
val BookProgress?.isStarted: Boolean get() = this != null && !finished && positionMs > 0

/**
 * "Keep episodes: the newest n": of the episodes (newest first, possibly mixed from several subscriptions), per subscription ([group])
 * those older than the newest [keep] (0 = all) are dropped, provided they were never started ([untouched]). Started and played ones
 * stay, as does everything in the feed – this only affects the display.
 */
fun <T> List<T>.keepNewest(group: (T) -> String, keep: (group: String) -> Int, untouched: (T) -> Boolean): List<T> {
    val seen = HashMap<String, Int>()
    return filter { item ->
        val key = group(item)
        val index = seen.getOrDefault(key, 0)
        seen[key] = index + 1
        val limit = keep(key)
        limit <= 0 || index < limit || !untouched(item)
    }
}

/** The episodes of a subscription (newest first) by [filter], with [oldestFirst] the oldest first. */
fun List<BookWithProgress>.filterEpisodes(filter: EpisodeFilter, oldestFirst: Boolean, downloaded: Set<Long>): List<BookWithProgress> {
    val shown = when (filter) {
        EpisodeFilter.ALL -> this
        EpisodeFilter.UNHEARD -> filter { it.progress.isUnheard }
        EpisodeFilter.STARTED -> filter { it.progress.isStarted }
        // Episodes from the device are already there, without a download (as with books).
        EpisodeFilter.DOWNLOADED -> filter { it.book.isLocal || it.book.id in downloaded }
        EpisodeFilter.STARRED -> filter { it.book.starred }
    }
    return if (oldestFirst) shown.asReversed() else shown
}

/** Subscriptions (by name) in the order [sort]; ties keep the order by name. */
fun List<PodcastFeed>.sortedFeeds(sort: FeedSort, stats: Map<String, FeedStats>): List<PodcastFeed> = when (sort) {
    FeedSort.NAME -> this
    FeedSort.NEWEST_EPISODE -> sortedByDescending { stats[it.id]?.newestEpisodeAt ?: 0 }
    FeedSort.RECENTLY_PLAYED -> sortedByDescending { stats[it.id]?.lastPlayedAt ?: 0 }
    FeedSort.MOST_NEW -> sortedByDescending { stats[it.id]?.newEpisodes ?: 0 }
}
