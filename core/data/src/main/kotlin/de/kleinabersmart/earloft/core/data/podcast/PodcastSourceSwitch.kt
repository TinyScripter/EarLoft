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
import de.kleinabersmart.earloft.core.network.podcast.FeedFetch
import de.kleinabersmart.earloft.core.network.podcast.ParsedEpisode
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Ergebnis der Prüfung einer neuen Quelle für ein Abo (Konzept 12.10, „Quelle wechseln“). */
sealed interface SourceCheck {
    /**
     * Bereit: [matched] der [episodes] Folgen des Abos haben in der neuen Quelle ein Gegenstück und behalten Fortschritt,
     * Downloads und Statistik. [keptUnmatched] angefangene oder gehörte Folgen ohne Gegenstück bleiben unter ihrer alten
     * Kennung stehen; die übrigen ohne Gegenstück verschwinden wie beim Abgleich.
     */
    class Ready internal constructor(
        internal val plan: SwitchPlan,
        val episodes: Int,
        val matched: Int,
        val keptUnmatched: Int,
    ) : SourceCheck

    /** Die neue Adresse ist die bisherige. */
    data object SameSource : SourceCheck

    /** Unter der neuen Adresse besteht schon ein (Probe-)Abo [title]. */
    data class Taken(val title: String) : SourceCheck

    /** Die neue Quelle ist ungültig oder ließ sich nicht laden. */
    data class Failed(val result: SubscribeResult) : SourceCheck
}

internal class SwitchPlan(
    val feedId: String,
    val oldUrl: String,
    val newUrl: String,
    val fetched: FeedFetch.Fetched,
    /** Folge (`bookId`) → ihr Gegenstück in der neuen Quelle, samt alter Kennung. */
    val matches: Map<Long, Match>,
) {
    class Match(val oldGuid: String, val episode: ParsedEpisode)
}

/**
 * Wechselt die Quelle eines Abos, etwa vom RSS-Feed zu ARD Sounds (Konzept 12.10). Das Abo bleibt dasselbe (`feedId`), seine
 * Folgen behalten ihre `bookId`: Fortschritt, Downloads, Warteschlange, Uhr-Auswahl und Einstellungen bleiben. Umgeschrieben
 * werden die Feed-Adresse, die Kennungen (guid) und Audio-Adressen der Folgen, die Statistik (Gruppe = Feed-Adresse,
 * Eintrag = guid), die Schlüssel in Warteschlange und „zuletzt gespielt“ ([BookKeyRenamer]) und der gesicherte Fortschritt
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
    /** Lädt die neue Quelle [input] und ordnet die Folgen zu; ändert noch nichts. */
    suspend fun check(feedId: String, input: String): SourceCheck {
        val feed = podcastDao.feed(feedId) ?: return SourceCheck.Failed(SubscribeResult.NotAFeed)
        val source = when (val fetched = podcasts.fetchSource(input)) {
            is PodcastRepository.SourceFetch.Failed -> return SourceCheck.Failed(fetched.result)
            is PodcastRepository.SourceFetch.Loaded -> fetched
        }
        if (source.url == feed.url) return SourceCheck.SameSource
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
            ),
            episodes = books.size,
            matched = matches.size,
            keptUnmatched = books.count { it.bookId !in matches && it.bookId in progress },
        )
    }

    /** Führt den geprüften Wechsel aus. `false`, wenn sich das Abo seit der Prüfung geändert hat (nichts geändert). */
    suspend fun switch(ready: SourceCheck.Ready): Boolean {
        val plan = ready.plan
        val renamed = plan.matches.filterValues { it.oldGuid != it.episode.guid }
        val switched = podcasts.withFeedLock {
            val feed = podcastDao.feed(plan.feedId)
            val taken = podcastDao.feedByUrl(plan.newUrl)?.let { it.feedId != plan.feedId } ?: false
            if (feed == null || feed.url != plan.oldUrl || taken) return@withFeedLock false
            database.withTransaction {
                // In zwei Schritten: Die neue Kennung einer Folge kann die alte einer anderen sein.
                renamed.keys.forEach { podcastDao.setRemoteId(it, TEMP_PREFIX + it) }
                renamed.forEach { (bookId, match) -> podcastDao.setRemoteId(bookId, match.episode.guid) }
                // Dieselbe Folge: Der Download bleibt, auch wenn die Datei jetzt unter einer anderen Adresse liegt.
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
        val keys = renamed.values.associate {
            BookKeys.encode(plan.feedId, it.oldGuid) to BookKeys.encode(plan.feedId, it.episode.guid)
        }
        if (keys.isNotEmpty()) renamers.forEach { it.renameBookKeys(keys) }
        userDataBackup.forgetProgress(plan.feedId, renamed.values.map { it.oldGuid })
        Log.i(TAG, "Quelle gewechselt: ${plan.matches.size} Folgen zugeordnet, ${renamed.size} mit neuer Kennung")
        return true
    }

    private companion object {
        const val TAG = "PodcastSourceSwitch"
        const val MAX_ARGS = 500

        /** Übergangskennung beim Umbenennen; kommt in keinem Feed vor. */
        const val TEMP_PREFIX = "\u0000switch:"
    }
}

/** Eine Folge des Abos vor dem Wechsel: Kennung, Audio-Adresse, Titel und Datum (Erscheinen bzw. erstes Auftauchen). */
internal data class KnownEpisode(val bookId: Long, val guid: String, val audioUrl: String?, val title: String, val publishedAt: Long)

/**
 * Ordnet die Folgen [known] eines Abos ihren Gegenstücken in [fresh] zu: erst über die Kennung, dann über die Adresse der
 * Audiodatei (ohne Schema und Query), zuletzt über den Titel, wenn das Datum höchstens [MAX_DATE_DIFF_MS] abweicht. Jede
 * Folge in [fresh] gehört höchstens zu einer bekannten; eine doppelte Kennung zählt wie beim Abgleich nur das erste Mal.
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

/** Adresse einer Audiodatei ohne Schema, Query und Groß-/Kleinschreibung des Hosts; Feeds hängen oft Zähl-Parameter an. */
private fun audioKey(url: String): String = normalizeFeedUrl(url)?.let { it.host + it.encodedPath } ?: url.trim()

/** Folgen erscheinen in RSS-Feed und ARD Sounds nicht immer zur selben Stunde. */
private const val MAX_DATE_DIFF_MS = 2 * 24 * 60 * 60 * 1000L

/**
 * Die Statistik der Gruppen [oldGroup] und [newGroup] nach dem Wechsel: alles unter [newGroup], Einträge mit neuer
 * Kennung laut [renames]. Treffen dabei zwei Einträge zusammen (etwa weil die neue Quelle schon einmal abonniert war),
 * zählen sie zusammen.
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

/** Wie [rekeyStats] für die Tage. */
internal fun rekeyDays(rows: List<ListeningDayEntity>, oldGroup: String, newGroup: String): List<ListeningDayEntity> = rows
    .map { if (it.groupKey == oldGroup) it.copy(groupKey = newGroup) else it }
    .groupBy { Triple(it.day, it.kind, it.groupKey) }
    .map { (_, same) -> same.reduce { a, b -> a.copy(listenedMs = a.listenedMs + b.listenedMs) } }
