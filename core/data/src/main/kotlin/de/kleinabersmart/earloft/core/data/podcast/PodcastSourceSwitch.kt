package de.kleinabersmart.earloft.core.data.podcast

import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.spoken.BookKeyRenamer
import de.kleinabersmart.earloft.core.data.spoken.BookKeys
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.entity.ListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.network.podcast.FeedAuth
import de.kleinabersmart.earloft.core.network.podcast.FeedFetch
import de.kleinabersmart.earloft.core.network.podcast.ParsedEpisode
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Result of checking a new source for a subscription (concept 12.10, "Switch source"). */
sealed interface SourceCheck {
    /**
     * Ready: [matched] of the subscription's [episodes] episodes have a counterpart in the new source and keep progress,
     * downloads and statistics. [keptUnmatched] started or played episodes without a counterpart stay under their old
     * ID; the other ones without a counterpart disappear as during a sync.
     */
    class Ready internal constructor(
        internal val plan: SwitchPlan,
        val episodes: Int,
        val matched: Int,
        val keptUnmatched: Int,
    ) : SourceCheck

    /** The new address is the current one. */
    data object SameSource : SourceCheck

    /** A (trial) subscription [title] already exists under the new address. */
    data class Taken(val title: String) : SourceCheck

    /** The new source is invalid or could not be loaded. */
    data class Failed(val result: SubscribeResult) : SourceCheck
}

internal class SwitchPlan(
    val feedId: String,
    val oldUrl: String,
    val newUrl: String,
    val fetched: FeedFetch.Fetched,
    /** Episode (`bookId`) → its counterpart in the new source, including the old ID. */
    val matches: Map<Long, Match>,
    /** Credentials with which the new source was loaded (concept 12.10 "Protected feeds"). */
    val auth: FeedAuth? = null,
) {
    class Match(val oldGuid: String, val episode: ParsedEpisode)
}

/**
 * Switches the source of a subscription, e.g. from the RSS feed to ARD Sounds (concept 12.10). The subscription stays the same (`feedId`), its
 * episodes keep their `bookId`: progress, downloads, queue, watch selection and settings stay. Rewritten
 * are the feed URL, the IDs (guid) and audio URLs of the episodes, the statistics (group = feed URL,
 * entry = guid), the keys in the queue and "recently played" ([BookKeyRenamer]) and the backed-up progress
 * in `user.db`.
 */
@Singleton
class PodcastSourceSwitch @Inject constructor(
    private val database: EarLoftDatabase,
    private val podcasts: PodcastRepository,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val statsDao: StatsDao,
    private val userDataBackup: UserDataBackup,
    private val renamers: Set<@JvmSuppressWildcards BookKeyRenamer>,
) {
    /** Loads the new source [input] and matches the episodes; doesn't change anything yet. */
    suspend fun check(feedId: String, input: String): SourceCheck {
        val feed = podcastDao.feed(feedId) ?: return SourceCheck.Failed(SubscribeResult.NotAFeed)
        val source = when (val fetched = podcasts.fetchSource(input, podcasts.storedCredentials(feedId))) {
            is PodcastRepository.SourceFetch.Failed -> return SourceCheck.Failed(fetched.result)
            is PodcastRepository.SourceFetch.Loaded -> fetched
        }
        if (sameAddress(source.url, feed.url)) return SourceCheck.SameSource
        podcastDao.feedByUrl(source.url)?.takeIf { it.feedId != feedId }?.let { return SourceCheck.Taken(it.title) }
        val books = bookDao.booksOf(feedId)
        val known = books.map { book ->
            KnownEpisode(book.bookId, book.remoteId, podcastDao.trackOf(book.bookId)?.remoteFileId, book.title, book.addedAt)
        }
        val matches = matchEpisodes(known, source.fetched.feed.episodes)
        val progress = books.map { it.bookId }.chunked(MAX_ARGS).flatMap { bookDao.progressOf(it) }
            .filter { it.finished || it.positionMs > 0 }
            .mapTo(HashSet()) { it.bookId }
        val guids = known.associate { it.bookId to it.guid }
        return SourceCheck.Ready(
            plan = SwitchPlan(
                feedId,
                feed.url,
                source.url,
                source.fetched,
                matches.mapValues { (bookId, episode) -> SwitchPlan.Match(guids.getValue(bookId), episode) },
                source.auth,
            ),
            episodes = books.size,
            matched = matches.size,
            keptUnmatched = books.count { it.bookId !in matches && it.bookId in progress },
        )
    }

    /** Performs the checked switch. `false` if the subscription has changed since the check (nothing changed). */
    suspend fun switch(ready: SourceCheck.Ready): Boolean {
        val plan = ready.plan
        val renamed = plan.matches.filterValues { it.oldGuid != it.episode.guid }
        val switched = podcasts.withFeedLock {
            val feed = podcastDao.feed(plan.feedId)
            val taken = podcastDao.feedByUrl(plan.newUrl)?.let { it.feedId != plan.feedId } ?: false
            if (feed == null || feed.url != plan.oldUrl || taken) return@withFeedLock false
            database.withTransaction {
                // In two steps: the new ID of one episode can be the old one of another.
                renamed.keys.forEach { podcastDao.setRemoteId(it, TEMP_PREFIX + it) }
                renamed.forEach { (bookId, match) -> podcastDao.setRemoteId(bookId, match.episode.guid) }
                // Same episode: the download stays, even if the file is now at a different URL.
                plan.matches.forEach { (bookId, match) -> podcastDao.moveTrack(bookId, match.episode.audioUrl) }
                val guidRenames = renamed.values.associate { it.oldGuid to it.episode.guid }
                val groups = listOf(plan.oldUrl, plan.newUrl)
                statsDao.replaceGroups(
                    groups,
                    rekeyStats(statsDao.statsOfGroups(groups), plan.oldUrl, plan.newUrl, guidRenames),
                    rekeyDays(statsDao.daysOfGroups(groups), plan.oldUrl, plan.newUrl),
                )
                val moved = feed.copy(url = plan.newUrl, etag = null, lastModified = null)
                podcastDao.upsertFeed(moved)
                podcasts.applyLocked(moved, plan.fetched)
            }
            true
        }
        if (!switched) return false
        plan.auth?.let { podcasts.storeCredentials(plan.feedId, it) }
        val keys = renamed.values.associate {
            BookKeys.encode(plan.feedId, it.oldGuid) to BookKeys.encode(plan.feedId, it.episode.guid)
        }
        if (keys.isNotEmpty()) renamers.forEach { it.renameBookKeys(keys) }
        userDataBackup.forgetProgress(plan.feedId, renamed.values.map { it.oldGuid })
        Log.i(TAG, "Quelle gewechselt: ${plan.matches.size} Folgen zugeordnet, ${renamed.size} mit neuer Kennung")
        return true
    }

    /** Moves that failed (target not a feed, already subscribed …); they are not retried until restart. */
    private val refusedMoves = Collections.synchronizedSet(HashSet<Pair<String, String>>())

    /**
     * Feed move without confirmation (concept 12.10): if the feed announces a new address (`itunes:new-feed-url`) or
     * permanently redirects, the subscription moves there as with "Switch source". Only if there is a feed that is not
     * another subscription, doesn't point back to the old address and isn't unencrypted where it was HTTPS before.
     * `true` if the subscription moved.
     */
    suspend fun follow(feedId: String, newUrl: String): Boolean {
        val old = podcastDao.feed(feedId) ?: return false
        val oldUrl = old.url
        if (sameAddress(oldUrl, newUrl) || !refusedMoves.add(feedId to newUrl)) return false
        val ready = when (val check = check(feedId, newUrl)) {
            is SourceCheck.Ready -> check
            else -> {
                Log.i(TAG, "Kein Umzug nach ${hostOf(newUrl)}: $check")
                return false
            }
        }
        val target = ready.plan.newUrl
        val fetched = ready.plan.fetched
        val downgrade = oldUrl.startsWith("https://", ignoreCase = true) && !target.startsWith("https://", ignoreCase = true)
        val pointsBack = listOfNotNull(fetched.feed.newFeedUrl, fetched.movedTo).any { sameAddress(it, oldUrl) }
        if (downgrade || pointsBack) {
            Log.i(TAG, "Kein Umzug nach ${hostOf(target)}: unverschlüsselt oder zurück auf die alte Adresse")
            return false
        }
        // If both feeds name their ID (`podcast:guid`), they must be equal: otherwise there is a different podcast there.
        if (!samePodcast(old.podcastGuid, fetched.feed.podcastGuid)) {
            Log.i(TAG, "Kein Umzug nach ${hostOf(target)}: anderer Podcast (podcast:guid)")
            return false
        }
        if (!switch(ready)) return false
        refusedMoves.remove(feedId to newUrl)
        Log.i(TAG, "Feed umgezogen: ${hostOf(oldUrl)} → ${hostOf(target)}")
        return true
    }

    private companion object {
        const val TAG = "PodcastSourceSwitch"
        const val MAX_ARGS = 500

        /** Temporary ID during renaming; doesn't occur in any feed. */
        const val TEMP_PREFIX = "\u0000switch:"
    }
}

/**
 * Whether two feed URLs are the same, even with different spelling (case of the host, default port);
 * addresses that are not URLs (ARD Sounds) only when equal.
 */
internal fun sameAddress(a: String, b: String): Boolean = a == b || a.toHttpUrlOrNull()?.let { it == b.toHttpUrlOrNull() } == true

/** Whether two feeds can be the same podcast: if one ID (`podcast:guid`) is missing, it decides nothing. */
internal fun samePodcast(oldGuid: String?, newGuid: String?): Boolean =
    oldGuid == null || newGuid == null || oldGuid.equals(newGuid, ignoreCase = true)

/** An episode of the subscription before the switch: ID, audio URL, title and date (published or first seen). */
internal data class KnownEpisode(val bookId: Long, val guid: String, val audioUrl: String?, val title: String, val publishedAt: Long)

/**
 * Matches the episodes [known] of a subscription to their counterparts in [fresh]: first by ID, then by the URL of the
 * audio file (without scheme and query), finally by title if the date differs by at most [MAX_DATE_DIFF_MS]. Each
 * episode in [fresh] belongs to at most one known episode; a duplicate ID only counts the first time, as during a sync.
 */
internal fun matchEpisodes(known: List<KnownEpisode>, fresh: List<ParsedEpisode>): Map<Long, ParsedEpisode> {
    val candidates = fresh.distinctBy { it.guid }.toMutableList()
    val result = LinkedHashMap<Long, ParsedEpisode>()
    fun pass(key: (KnownEpisode) -> String?, freshKey: (ParsedEpisode) -> String?, fits: (KnownEpisode, ParsedEpisode) -> Boolean = { _, _ -> true }) {
        val byKey = candidates.groupBy { freshKey(it) }
        known.filter { it.bookId !in result }.forEach { episode ->
            val k = key(episode) ?: return@forEach
            val match = byKey[k].orEmpty().firstOrNull { it in candidates && fits(episode, it) } ?: return@forEach
            result[episode.bookId] = match
            candidates.remove(match)
        }
    }
    pass({ it.guid }, { it.guid })
    pass({ it.audioUrl?.let(::audioKey) }, { audioKey(it.audioUrl) })
    pass(
        { it.title.normalizeForMerge().ifEmpty { null } },
        { it.title.normalizeForMerge().ifEmpty { null } },
        { old, new -> old.publishedAt <= 0 || new.publishedAt <= 0 || abs(old.publishedAt - new.publishedAt) <= MAX_DATE_DIFF_MS },
    )
    return result
}

/** URL of an audio file without scheme, query and host case; feeds often append tracking parameters. */
private fun audioKey(url: String): String = normalizeFeedUrl(url)?.let { it.host + it.encodedPath } ?: url.trim()

/** Only the host for the log: private feeds carry their access token in the path or query. */
private fun hostOf(url: String): String = normalizeFeedUrl(url)?.host ?: url.substringBefore(':')

/** Episodes don't always appear in the RSS feed and on ARD Sounds at the same hour. */
private const val MAX_DATE_DIFF_MS = 2 * 24 * 60 * 60 * 1000L

/**
 * The statistics of the groups [oldGroup] and [newGroup] after the switch: everything under [newGroup], entries with a new
 * ID according to [renames]. If two entries collide (e.g. because the new source was subscribed before),
 * they are added together.
 */
internal fun rekeyStats(
    rows: List<ListeningStatEntity>,
    oldGroup: String,
    newGroup: String,
    renames: Map<String, String>,
): List<ListeningStatEntity> = rows
    .map { if (it.groupKey == oldGroup) it.copy(groupKey = newGroup, itemKey = renames[it.itemKey] ?: it.itemKey) else it }
    .groupBy { Triple(it.source, it.groupKey, it.itemKey) }
    .map { (_, same) ->
        same.reduce { a, b ->
            val newer = if (b.lastListenedAt > a.lastListenedAt) b else a
            newer.copy(listenedMs = a.listenedMs + b.listenedMs)
        }.copy(statId = 0)
    }

/** Like [rekeyStats] for the days. */
internal fun rekeyDays(rows: List<ListeningDayEntity>, oldGroup: String, newGroup: String): List<ListeningDayEntity> = rows
    .map { if (it.groupKey == oldGroup) it.copy(groupKey = newGroup) else it }
    .groupBy { Triple(it.day, it.kind, it.groupKey) }
    .map { (_, same) -> same.reduce { a, b -> a.copy(listenedMs = a.listenedMs + b.listenedMs) } }
