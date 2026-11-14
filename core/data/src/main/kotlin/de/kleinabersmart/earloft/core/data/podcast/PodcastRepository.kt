package de.kleinabersmart.earloft.core.data.podcast

import android.text.Html
import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.EpisodePersonEntity
import de.kleinabersmart.earloft.core.model.EpisodeType
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.data.refresh.NewEpisodes
import de.kleinabersmart.earloft.core.data.spoken.toModel
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.ChapterSource
import de.kleinabersmart.earloft.core.model.EffectOverrides
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.FeedStats
import de.kleinabersmart.earloft.core.model.keepNewest
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.podcast.FeedAuth
import de.kleinabersmart.earloft.core.network.podcast.FeedFetch
import de.kleinabersmart.earloft.core.network.podcast.Opml
import de.kleinabersmart.earloft.core.network.podcast.ParsedChapter
import de.kleinabersmart.earloft.core.network.podcast.OpmlFeed
import de.kleinabersmart.earloft.core.network.podcast.ParsedEpisode
import de.kleinabersmart.earloft.core.network.podcast.ParsedFeed
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedException
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsBridgeUrl
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsShowId
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.parseArdSoundsInput
import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl
import de.kleinabersmart.earloft.core.network.podcast.splitCredentials
import de.kleinabersmart.earloft.core.network.podcast.directory.feedKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.net.ssl.SSLException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SubscribeResult {
    data class Added(val feedId: String) : SubscribeResult
    data class AlreadySubscribed(val feedId: String) : SubscribeResult

    /** Das Abo besteht schon, ist aber archiviert; es lässt sich mit [PodcastRepository.reactivate] zurückholen. */
    data class Archived(val feedId: String, val title: String) : SubscribeResult
    data object InvalidUrl : SubscribeResult
    data object Unreachable : SubscribeResult
    data object NotAFeed : SubscribeResult

    /**
     * Der Feed verlangt eine Anmeldung (HTTP 401, Konzept 12.10 „Geschützte Feeds“); [rejected]: Die mitgeschickten
     * Zugangsdaten wurden abgelehnt.
     */
    data class CredentialsNeeded(val rejected: Boolean) : SubscribeResult
}

/** Was ein OPML-Import getan hat: neue Abos, übersprungene Dubletten, beim Ersetzen entfernte Abos. */
data class OpmlImportResult(val added: Int, val skipped: Int, val removed: Int)

/** Ergebnis eines Feed-Abgleichs: `null` bei Fehler, sonst die Folgen, die neu im Feed stehen. */
data class RefreshResult(val newEpisodeIds: List<Long>)

/**
 * Ergebnis von [PodcastRepository.refreshAll]: `ok`, wenn kein Feed fehlgeschlagen ist, und die Abos mit neuen Folgen.
 * [allFailed]: Kein Feed war erreichbar – eher das Netz als einzelne tote Feeds. [moved]: Titel der Abos, die an eine neue
 * Adresse umgezogen sind.
 */
data class PodcastRefreshSummary(
    val ok: Boolean,
    val newEpisodes: List<NewEpisodes>,
    val allFailed: Boolean = false,
    val moved: List<String> = emptyList(),
) {
    val newCount: Int get() = newEpisodes.sumOf { it.count }
}

/** Ein Abo mit einer Auswahl seiner Folgen. */
data class FeedEpisodes(val feed: PodcastFeed, val episodes: List<BookWithProgress>)

/** Ein Podcast mit seinen fertig geladenen Folgen (Anzahl, belegte Bytes). */
data class FeedDownload(val feed: PodcastFeed, val episodes: Int, val bytes: Long)

/**
 * Eine gehörte Folge im Verlauf; Titel wie zuletzt gehört. [feedId] und [bookId] fehlen, wenn es das Abo bzw. die Folge nicht
 * mehr gibt; [listenedMs] ist die Inhaltszeit.
 */
data class EpisodeHistoryEntry(
    val title: String,
    val podcast: String,
    val lastListenedAt: Long,
    val listenedMs: Long,
    val feedId: String?,
    val bookId: Long?,
    val imageUrl: String?,
    val durationMs: Long,
    val finished: Boolean,
)

/** Podcast-Abos und ihre Folgen (Konzept 12.1): abonnieren, abgleichen, Kapitel nachladen. */
@Singleton
class PodcastRepository @Inject constructor(
    private val database: EarLoftDatabase,
    private val dao: PodcastDao,
    private val bookDao: BookDao,
    private val api: PodcastFeedApi,
    private val ardSounds: ArdSoundsApi,
    private val settings: PodcastSettings,
    private val fileChapters: AudioFileChapters,
    // Lazy: Der Wechsel der Quelle braucht seinerseits dieses Repository.
    private val sourceSwitch: dagger.Lazy<PodcastSourceSwitch>,
    private val credentials: FeedCredentials,
) {
    private val mutex = Mutex()

    private val _refreshed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Meldet die Id jedes Podcasts, dessen Feed gerade abgeglichen wurde (auch ohne Änderung), für die Download-Automatik. */
    val refreshed: SharedFlow<String> = _refreshed.asSharedFlow()

    val feeds: Flow<List<PodcastFeed>> = dao.observeFeeds().map { list -> list.map { it.toModel() } }

    /** Abos, deren Titel oder Autor [query] enthält (ohne Rücksicht auf Groß-/Kleinschreibung und Akzente). */
    suspend fun searchFeeds(query: String): List<PodcastFeed> {
        val needle = query.normalizeForMerge()
        if (needle.isEmpty()) return emptyList()
        return feeds.first().filter { it.title.normalizeForMerge().contains(needle) || it.author.normalizeForMerge().contains(needle) }
    }

    /** Podcasts, von denen mindestens eine Folge geladen ist, nach Titel. */
    fun feedDownloads(): Flow<List<FeedDownload>> = combine(dao.observeAllFeeds(), dao.observeFeedDownloads()) { feeds, rows ->
        val byFeed = rows.associateBy { it.feedId }
        feeds.mapNotNull { feed -> byFeed[feed.feedId]?.let { FeedDownload(feed.toModel(), it.episodes, it.bytes) } }
    }

    /** Zahl der fertig geladenen Folgen je Podcast (Markierung der Abo-Kacheln); Podcasts ohne fehlen. */
    fun downloadedEpisodeCounts(): Flow<Map<String, Int>> =
        dao.observeFeedDownloads().map { rows -> rows.associate { it.feedId to it.episodes } }.distinctUntilChanged()

    /** Download-Zustand aller Folgen mit Download (Markierung der Kacheln); Folgen ohne Eintrag sind nicht geladen. */
    fun episodeDownloadStates(): Flow<Map<Long, TrackDownload>> =
        dao.observeEpisodeDownloads().map { rows -> rows.associate { it.bookId to it.state } }.distinctUntilChanged()

    fun feed(feedId: String): Flow<PodcastFeed?> = dao.observeFeed(feedId).map { it?.toModel() }

    /** Archivierte Abos nach Titel (Einstellungen → Podcasts). */
    val archivedFeeds: Flow<List<PodcastFeed>> = dao.observeArchivedFeeds().map { list -> list.map { it.toModel() } }

    /** Die Abos nach [feedKey] ihrer Adresse, um Verzeichnistreffer als schon abonniert zu erkennen. */
    val subscribedByKey: Flow<Map<String, String>> = feeds.map { list -> list.filter { !it.isLocal }.associate { feedKey(it.url) to it.id } }

    /** Download-Zustand je Folge eines Podcasts (Folgen ohne Eintrag sind nicht geladen). */
    fun downloadStates(feedId: String): Flow<Map<Long, TrackDownload>> =
        dao.observeDownloadStates(feedId).map { rows -> rows.associate { it.bookId to it.state } }

    /**
     * Folgen eines Podcasts, neueste zuerst, mit Fortschritt; ohne die, die „Folgen behalten“ ausblendet ([keepNewest]), und
     * ohne Trailer, sobald es echte Folgen gibt ([withoutTrailers]).
     */
    fun episodes(feedId: String): Flow<List<BookWithProgress>> =
        combine(dao.observeEpisodesOf(feedId), bookDao.observeAllProgress(), dao.observeFeed(feedId)) { episodes, progress, feed ->
            val byBook = progress.associateBy { it.bookId }
            episodes
                .withoutTrailers(byBook)
                .keepNewest({ feedId }, { feed?.keepEpisodes ?: 0 }) { byBook[it.bookId].isUntouched && it.starredAt == null }
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
        }

    /**
     * Je Abo die neueste Folge, der zuletzt gehörte Zeitpunkt und die Zahl der neuen Folgen ([FeedStats.newEpisodes], ohne die
     * von „Folgen behalten“ ausgeblendeten), für Sortierung und Kacheln.
     */
    fun feedStats(): Flow<Map<String, FeedStats>> =
        combine(dao.observeFeeds(), dao.observeEpisodes(), bookDao.observeAllProgress()) { feeds, episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            episodes.visibleIn(feeds, byBook).groupBy { it.serverId }.mapValues { (_, list) ->
                FeedStats(
                    newestEpisodeAt = list.first().addedAt,
                    lastPlayedAt = list.maxOf { e -> byBook[e.bookId]?.takeUnless { it.isUntouched }?.updatedAt ?: 0L },
                    // Die Liste ist neueste zuerst: alles vor der ersten angefangenen oder gehörten Folge.
                    newEpisodes = list.takeWhile { byBook[it.bookId].isUntouched }.size,
                )
            }
        }.distinctUntilChanged()

    /** Pro Abo (nach Titel) die Folgen, die noch nicht zu Ende gehört sind, neueste zuerst; Abos ohne solche Folgen entfallen. */
    fun unheardByFeed(): Flow<List<FeedEpisodes>> =
        combine(dao.observeFeeds(), dao.observeEpisodes(), bookDao.observeAllProgress()) { feeds, episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            val byFeed = episodes
                .visibleIn(feeds, byBook)
                .filter { byBook[it.bookId]?.finished != true }
                .groupBy { it.serverId }
            feeds.mapNotNull { feed ->
                byFeed[feed.feedId]?.let { list ->
                    FeedEpisodes(feed.toModel(), list.map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) })
                }
            }
        }

    /**
     * „Zuletzt hinzugefügt“ auf Start: die neuesten Folgen aller Abos nach Erscheinen, ohne zu Ende gehörte. Folgen aus
     * „Weiterhören“ bleiben drin, denn dort stehen neue Folgen ohnehin von selbst, nur in der Reihenfolge der Warteschlange.
     */
    fun recentEpisodes(limit: Int = RECENT_LIMIT): Flow<List<BookWithProgress>> =
        combine(dao.observeFeeds(), dao.observeEpisodes(), bookDao.observeAllProgress()) { feeds, episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            episodes.visibleIn(feeds, byBook).asSequence()
                .filter { byBook[it.bookId]?.finished != true }
                .sortedByDescending { it.addedAt }
                .take(limit)
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
                .toList()
        }

    /**
     * „Weiterhören“ (die Warteschlange der Podcasts): ganz vorn die Folgen aus [order] (nicht zu Ende gehörte, in dieser
     * Reihenfolge), dahinter von selbst die angefangenen Folgen mit der zuletzt gehörten zuerst und die neuen, noch nicht
     * angefangenen Folgen der letzten [NEW_DAYS] Tage (neueste zuerst) – je nach [FeedQueueMode] des Abos alle, nur seine
     * neueste oder keine. Folgen in [hidden] fehlen.
     */
    fun continueListening(
        order: Flow<List<Long>> = flowOf(emptyList()),
        hidden: Flow<Set<Long>> = flowOf(emptySet()),
        now: () -> Long = System::currentTimeMillis,
    ): Flow<List<BookWithProgress>> =
        combine(
            combine(dao.observeEpisodes(), dao.observeTrialEpisodes(), dao.observeFeeds(), ::Triple),
            bookDao.observeAllProgress(),
            order,
            hidden,
        ) { (episodes, trial, feeds), progress, orderIds, hiddenIds ->
            val byBook = progress.associateBy { it.bookId }
            val modes = feeds.associate { it.feedId to it.queueMode }
            val visible = episodes.visibleIn(feeds, byBook)
            // Die neueste Folge jedes Abos (die Liste ist nach Datum absteigend), für „Nur die neueste“; ein Trailer zählt
            // dabei nicht, wenn es echte Folgen gibt.
            val newest = HashMap<String, Long>()
            visible.forEach { newest.putIfAbsent(it.serverId, it.bookId) }
            val started = episodes
                .filter { it.bookId !in hiddenIds }
                .mapNotNull { e -> byBook[e.bookId]?.takeIf { !it.finished && it.positionMs > 0 }?.let { e to it } }
                .sortedByDescending { (_, p) -> p.updatedAt }
                .map { (e, p) -> BookWithProgress(e.toModel(), p.toModel()) }
            val since = now() - NEW_DAYS * DAY_MS
            val fresh = visible
                .filter { e -> e.bookId !in hiddenIds && byBook[e.bookId].isUntouched && e.addedAt >= since }
                .filter { e ->
                    when (modes[e.serverId]) {
                        FeedQueueMode.MANUAL -> false
                        FeedQueueMode.NEWEST_ONLY -> newest[e.serverId] == e.bookId
                        FeedQueueMode.ALL_NEW, null -> true
                    }
                }
                .take(MAX_NEW)
                .map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
            // Folgen der Probeabos stehen nur hier, wo sie ausdrücklich eingereiht wurden (Konzept 12.10).
            val byId = (episodes + trial).associateBy { it.bookId }
            val ordered = orderIds.mapNotNull { id ->
                byId[id]?.takeIf { id !in hiddenIds && byBook[id]?.finished != true }
                    ?.let { BookWithProgress(it.toModel(), byBook[id]?.toModel()) }
            }
            val placed = ordered.mapTo(HashSet()) { it.book.id }
            ordered + (started + fresh).filter { it.book.id !in placed }
        }

    suspend fun isEpisode(bookId: Long): Boolean = bookDao.book(bookId)?.kind == BookKind.EPISODE

    /** Eine zu Ende gehörte Folge, die neu startet, gilt wieder als offen (sie beginnt ohnehin von vorn). */
    suspend fun reopenIfFinished(bookId: Long) {
        if (bookDao.progress(bookId)?.finished == true) setPlayed(bookId, played = false)
    }

    /** Ids aller fertig geladenen Folgen. */
    fun downloadedIds(): Flow<Set<Long>> = dao.observeDownloadedIds().map { it.toSet() }

    /** Gemerkte Folgen der Abos (Reiter „Gemerkt“), zuletzt gemerkte zuerst, ohne Beschreibung. */
    fun starredEpisodes(): Flow<List<BookWithProgress>> =
        combine(dao.observeStarredEpisodes(), bookDao.observeAllProgress()) { episodes, progress ->
            val byBook = progress.associateBy { it.bookId }
            episodes.map { BookWithProgress(it.toModel(), byBook[it.bookId]?.toModel()) }
        }

    /**
     * Merkt die Folgen bzw. nimmt den Stern weg. Gemerkte Folgen bleiben, auch wenn der Feed sie nicht mehr führt, und ihr
     * Download wird nicht aufgeräumt; schon gemerkte behalten ihren Zeitpunkt. @return Zahl der geänderten Folgen.
     */
    suspend fun setStarred(bookIds: Collection<Long>, starred: Boolean): Int =
        bookIds.distinct().chunked(MAX_ARGS).sumOf { dao.setStarred(it, if (starred) System.currentTimeMillis() else null) }

    /**
     * Verlauf der gehörten Folgen ([limit] zuletzt gehörte), aus der Statistik: auch Folgen, die nicht mehr im Feed stehen,
     * und solche beendeter Abos; die Folge selbst nur, solange es sie gibt.
     */
    fun history(limit: Int = HISTORY_LIMIT): Flow<List<EpisodeHistoryEntry>> =
        combine(dao.observeHistory(limit), bookDao.observeAllProgress()) { rows, progress ->
            val byBook = progress.associateBy { it.bookId }
            rows.map { row ->
                EpisodeHistoryEntry(
                    title = row.itemTitle,
                    podcast = row.groupTitle,
                    lastListenedAt = row.lastListenedAt,
                    listenedMs = row.listenedMs,
                    feedId = row.feedId,
                    bookId = row.bookId,
                    imageUrl = row.imageUrl,
                    durationMs = row.durationMs ?: 0,
                    finished = row.bookId?.let { byBook[it]?.finished } == true,
                )
            }
        }.distinctUntilChanged()

    /** Markiert die Folge als gespielt (Position ans Ende) oder als ungespielt (Position 0). Nur lokal, es gibt keinen Server. */
    suspend fun setPlayed(bookId: Long, played: Boolean) {
        val book = bookDao.book(bookId) ?: return
        bookDao.upsertProgress(playedProgress(book, bookDao.progress(bookId), played, System.currentTimeMillis()))
    }

    /**
     * Wie [setPlayed] für mehrere Folgen in einem Zug (Mehrfachauswahl und Wischen, Konzept 12.5). Das Ergebnis stellt den
     * Stand davor wieder her (Position, Tempo, „gehört“) für „Rückgängig“.
     */
    suspend fun setPlayed(bookIds: Collection<Long>, played: Boolean): suspend () -> Unit {
        val now = System.currentTimeMillis()
        val before = bookIds.distinct().associateWith { bookDao.progress(it) }
        val rows = before.mapNotNull { (id, old) -> bookDao.book(id)?.let { playedProgress(it, old, played, now) } }
        if (rows.isNotEmpty()) bookDao.upsertAllProgress(rows)
        return {
            // Als neueste Änderung, sonst überschriebe der gesicherte Stand (`user.db`) die Wiederherstellung.
            val restoredAt = System.currentTimeMillis()
            before.values.filterNotNull().map { it.copy(updatedAt = restoredAt) }.takeIf { it.isNotEmpty() }?.let { bookDao.upsertAllProgress(it) }
            before.filterValues { it == null }.keys.forEach { dao.deleteProgress(it) }
        }
    }

    /** „Alle als gehört markieren“: alle noch offenen Folgen des Abos in einem Zug; gibt ihre Anzahl zurück. */
    suspend fun setAllPlayed(feedId: String): Int {
        val finished = dao.finishedEpisodeIds(feedId).toSet()
        val now = System.currentTimeMillis()
        val rows = dao.episodeIdsOldestFirst(feedId)
            .filter { it !in finished }
            .mapNotNull { id -> bookDao.book(id)?.let { playedProgress(it, bookDao.progress(id), played = true, now) } }
        if (rows.isNotEmpty()) bookDao.upsertAllProgress(rows)
        return rows.size
    }

    private fun playedProgress(book: BookEntity, old: BookProgressEntity?, played: Boolean, now: Long): BookProgressEntity {
        val duration = book.durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0
        return BookProgressEntity(
            bookId = book.bookId,
            positionMs = if (played) duration else 0,
            durationMs = duration,
            finished = played,
            speed = old?.speed,
            updatedAt = now,
            skipSilence = old?.skipSilence,
            volumeBoostDb = old?.volumeBoostDb,
        )
    }

    /** Setzt die Folge an den Anfang zurück (auch den Zustand „gespielt“). */
    suspend fun resetPosition(bookId: Long) = setPlayed(bookId, played = false)

    /**
     * Abonniert den Feed unter [input]. Eine `http://`-Adresse wird zuerst still per HTTPS versucht und bei Erfolg so
     * gespeichert; die meisten Hosts liefern beides. Erst wenn das scheitert, wird unverschlüsselt abonniert.
     * Ein archiviertes Abo derselben Adresse bleibt archiviert und meldet [SubscribeResult.Archived]. Ein neues Abo lädt
     * automatisch, wenn [PodcastSettings.autoDownloadNew] an ist.
     */
    suspend fun subscribe(input: String, auth: Credentials? = null): SubscribeResult {
        val entered = FeedAddress.of(input, auth) ?: return SubscribeResult.InvalidUrl
        knownFeed(entered)?.let { feed ->
            if (feed.archived) return SubscribeResult.Archived(feed.feedId, feed.title)
            if (feed.subscribed) return SubscribeResult.AlreadySubscribed(feed.feedId)
            // Aus der Vorschau: Folgen, Fortschritt, Downloads und Warteschlange bleiben, es wird nur ein Abo daraus.
            subscribeTrial(feed.feedId)
            return SubscribeResult.Added(feed.feedId)
        }
        return fetchAndAdd(entered, subscribed = true)
    }

    /**
     * Öffnet die Vorschau des Feeds unter [input] (Konzept 12.10): Gibt es ihn schon (Abo oder Probeabo), dessen Id –
     * ein Probeabo wird dabei neu abgeglichen; sonst wird er geladen und als Probeabo (`subscribed = false`) angelegt.
     * Ergebnis wie bei [subscribe]: [SubscribeResult.Added] bzw. [SubscribeResult.AlreadySubscribed] tragen die Id, ein
     * archiviertes Abo meldet [SubscribeResult.Archived] (ohne Abgleich). [imageUrl] ist das Bild aus dem Verzeichnis; es
     * gilt, solange der Feed selbst keins nennt.
     */
    suspend fun openPreview(input: String, imageUrl: String? = null): SubscribeResult {
        val entered = FeedAddress.of(input) ?: return SubscribeResult.InvalidUrl
        knownFeed(entered)?.let { feed ->
            if (feed.archived) return SubscribeResult.Archived(feed.feedId, feed.title)
            if (feed.subscribed) return SubscribeResult.AlreadySubscribed(feed.feedId)
            refresh(feed.feedId)
            return SubscribeResult.Added(feed.feedId)
        }
        return fetchAndAdd(entered, subscribed = false, imageUrl)
    }

    /** Macht aus dem Probeabo [feedId] ein Abo, mit der Vorgabe für neue Abos zur Download-Automatik. */
    suspend fun subscribeTrial(feedId: String) = dao.subscribeTrial(feedId, settings.autoDownloadNew.first())

    /** Benutzername und Passwort, wie sie der Nutzer für einen geschützten Feed eingibt. */
    data class Credentials(val username: String, val password: String) {
        override fun toString() = "Credentials($username)"
    }

    /** Zugangsdaten des Abos (gelten nur auf dem Host, unter dem sie eingegeben wurden). */
    fun credentials(feedId: String): Flow<FeedAuth?> = credentials.observe(feedId)

    /**
     * Speichert neue Zugangsdaten für das Abo unter seiner jetzigen Adresse (`null` löscht sie) und gleicht es gleich ab.
     * `null`, wenn der Abgleich danach scheiterte.
     */
    suspend fun setCredentials(feedId: String, auth: Credentials?): RefreshResult? {
        val feed = dao.feed(feedId) ?: return null
        val scope = normalizeFeedUrl(feed.url) ?: return null
        credentials.set(feedId, auth?.let { FeedAuth(scope, it.username, it.password) })
        return refresh(feedId)
    }

    /** Nach „Quelle wechseln“ mit Zugangsdaten in der neuen Adresse. */
    internal suspend fun storeCredentials(feedId: String, auth: FeedAuth) = credentials.set(feedId, auth)

    /** Gespeicherte Zugangsdaten des Abos, für eine neue Quelle (sie gelten nur, wenn sie auf demselben Host liegt). */
    internal suspend fun storedCredentials(feedId: String): FeedAuth? = credentials.forFeed(feedId)

    /**
     * Abo-Adresse aus einer Eingabe: ein RSS-/Atom-Feed oder eine Sendung aus ARD Sounds (Abo-Adresse `ardsounds:<Kennung>`
     * oder Link der Sendungsseite, Konzept 12.10). Zugangsdaten in der Adresse (`https://name:passwort@host/…`) werden
     * herausgelöst; eingegebene [auth] gehen vor.
     */
    private sealed interface FeedAddress {
        data class Http(val url: HttpUrl, val auth: FeedAuth? = null) : FeedAddress
        data class ArdSounds(val url: String) : FeedAddress

        companion object {
            fun of(input: String, auth: Credentials? = null): FeedAddress? =
                parseArdSoundsInput(input)?.let(::ArdSounds) ?: normalizeFeedUrl(input)?.let { raw ->
                    val (url, inUrl) = raw.splitCredentials()
                    Http(url, auth?.let { FeedAuth(url, it.username, it.password) } ?: inUrl)
                }
        }
    }

    /** Der gespeicherte Feed zu [entered], auch unter der `https://`-Variante einer `http://`-Adresse. */
    private suspend fun knownFeed(entered: FeedAddress): PodcastFeedEntity? = when (entered) {
        is FeedAddress.ArdSounds -> dao.feedByUrl(entered.url)
        is FeedAddress.Http -> {
            val secure = if (entered.url.isHttps) null else entered.url.newBuilder().scheme("https").build()
            listOfNotNull(secure, entered.url).firstNotNullOfOrNull { dao.feedByUrl(it.toString()) }
        }
    }

    /** Lädt den Feed und legt ihn an. */
    private suspend fun fetchAndAdd(entered: FeedAddress, subscribed: Boolean, imageUrl: String? = null): SubscribeResult =
        when (val source = fetchSource(entered)) {
            is SourceFetch.Loaded -> add(source.url, source.fallbackTitle, source.fetched, subscribed, imageUrl, source.auth)
            is SourceFetch.Failed -> source.result
        }

    /**
     * Lädt den Feed bzw. die Sendung unter [input], ohne etwas anzulegen (Quelle wechseln, Konzept 12.10). Wie beim
     * Abonnieren wird eine `http://`-Adresse zuerst still per HTTPS versucht. [stored]: die Zugangsdaten des Abos; sie gehen
     * nur mit, wenn die neue Quelle auf demselben Host liegt und die Adresse selbst keine trägt.
     */
    internal suspend fun fetchSource(input: String, stored: FeedAuth? = null): SourceFetch {
        val entered = FeedAddress.of(input) ?: return SourceFetch.Failed(SubscribeResult.InvalidUrl)
        val withStored = if (entered is FeedAddress.Http && entered.auth == null) entered.copy(auth = stored) else entered
        return fetchSource(withStored)
    }

    /**
     * Eine `http://`-Adresse wird zuerst still per HTTPS versucht und bei Erfolg so verwendet; erst wenn das scheitert,
     * unverschlüsselt.
     */
    private suspend fun fetchSource(entered: FeedAddress): SourceFetch {
        if (entered is FeedAddress.ArdSounds) {
            val showId = checkNotNull(ardSoundsShowId(entered.url))
            return fetchForSubscribe(entered.url, "ARD Sounds") { FeedFetch.Fetched(ardSounds.fetch(showId), null, null) }
        }
        val url = (entered as FeedAddress.Http).url
        val auth = entered.auth
        val secure = if (url.isHttps) null else url.newBuilder().scheme("https").build()
        if (secure != null) {
            // Unter HTTPS gelten die Zugangsdaten nur noch dort (kein Zurück auf HTTP).
            val secureAuth = auth?.copy(scope = secure)
            val attempt = fetchForSubscribe(secure.toString(), secure.host, secureAuth) { api.fetch(secure, auth = secureAuth) }
            // Verlangt der Host per HTTPS eine Anmeldung, gibt es ihn dort; unverschlüsselt würde das Passwort lesbar.
            if (attempt is SourceFetch.Loaded || (attempt as SourceFetch.Failed).result is SubscribeResult.CredentialsNeeded) {
                return attempt
            }
            Log.i(TAG, "Kein HTTPS bei ${secure.host}, lade unverschlüsselt")
        }
        return fetchForSubscribe(url.toString(), url.host, auth) { api.fetch(url, auth = auth) }
    }

    /**
     * Geladene Quelle unter ihrer endgültigen Adresse [url]; [fallbackTitle] gilt, wenn sie keinen Titel nennt. [auth]: die
     * Zugangsdaten, mit denen sie geladen wurde.
     */
    internal sealed interface SourceFetch {
        data class Loaded(
            val url: String,
            val fallbackTitle: String,
            val fetched: FeedFetch.Fetched,
            val auth: FeedAuth? = null,
        ) : SourceFetch
        data class Failed(val result: SubscribeResult) : SourceFetch
    }

    /** [source] nennt im Log nur den Host: Private Feeds tragen ihr Zugangstoken in Pfad oder Query. */
    private suspend fun fetchForSubscribe(
        url: String,
        source: String,
        auth: FeedAuth? = null,
        fetch: suspend () -> FeedFetch,
    ): SourceFetch = try {
        SourceFetch.Loaded(url, source, fetch() as FeedFetch.Fetched, auth)
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpStatusException) {
        Log.w(TAG, "Feed nicht geladen: $source (HTTP ${e.status})")
        if (e.status == 401) {
            SourceFetch.Failed(SubscribeResult.CredentialsNeeded(rejected = auth != null))
        } else {
            SourceFetch.Failed(SubscribeResult.Unreachable)
        }
    } catch (e: PodcastFeedException) {
        Log.w(TAG, "Kein Feed: $source", e)
        SourceFetch.Failed(SubscribeResult.NotAFeed)
    } catch (e: IOException) {
        Log.w(TAG, "Feed nicht erreichbar: $source", e)
        SourceFetch.Failed(SubscribeResult.Unreachable)
    }

    private suspend fun add(
        url: String,
        fallbackTitle: String,
        fetched: FeedFetch.Fetched,
        subscribed: Boolean,
        imageUrl: String?,
        auth: FeedAuth?,
    ): SubscribeResult {
        val feed = PodcastFeedEntity(
            feedId = UUID.randomUUID().toString(),
            url = url,
            title = fetched.feed.title.ifBlank { fallbackTitle },
            imageUrl = imageUrl,
            autoDownload = subscribed && settings.autoDownloadNew.first(),
            subscribed = subscribed,
        )
        // Vor dem Anlegen: Die Download-Automatik eines neuen Abos braucht sie sofort.
        if (auth != null) credentials.set(feed.feedId, auth)
        mutex.withLock { apply(feed, fetched) }
        return SubscribeResult.Added(feed.feedId)
    }

    suspend fun unsubscribe(feedId: String) = dao.deleteFeed(feedId)

    /**
     * Archiviert das Abo: Es verschwindet aus allen Listen und wird nicht mehr abgeglichen; Folgen, Fortschritt und
     * Statistik bleiben. Die Downloads löscht der Aufrufer (`EpisodeDownloads.removeAllOf`).
     */
    suspend fun archive(feedId: String) = dao.setArchived(feedId, true)

    /**
     * Holt ein archiviertes Abo zurück und gleicht es gleich ab. Was seit dem Archivieren erschienen ist, gilt damit
     * als bekannt und löst keine Benachrichtigung aus. `false`, wenn der Abgleich fehlschlug (das Abo ist trotzdem aktiv).
     */
    suspend fun reactivate(feedId: String): Boolean {
        dao.setArchived(feedId, false)
        return refresh(feedId) != null
    }

    /** Ids der Probeabos (Konzept 12.10). */
    val trialFeedIds: Flow<List<String>> = dao.observeTrialFeedIds()

    /** Folgen der Probeabos mit Fortschritt und Download-Zustand, für das Aufräumen. */
    val trialEpisodes: Flow<List<TrialEpisode>> = dao.observeTrialEpisodeStates().map { rows ->
        rows.map { TrialEpisode(it.bookId, it.feedId, it.finished, it.hasProgress, it.downloadState.isDownloadPresent()) }
    }

    /** Vergisst die Stelle einer Folge (ihre Datei löscht `EpisodeDownloads`). */
    suspend fun forgetProgress(bookId: Long) = dao.deleteProgress(bookId)

    /**
     * Die Abos als OPML-Text. Sendungen aus ARD Sounds stehen darin als Feed der RSS-Bridge unter [rssBridge] (Konzept
     * 12.10); ohne Bridge fehlen sie wie die Podcasts vom Gerät, die keinen Feed haben.
     */
    suspend fun exportOpml(title: String, createdAt: String, rssBridge: String?): String =
        Opml.write(
            dao.feeds().filter { !it.isLocal }.sortedBy { it.title.lowercase() }.mapNotNull { feed ->
                val showId = ardSoundsShowId(feed.url) ?: return@mapNotNull OpmlFeed(feed.title, feed.url)
                rssBridge?.let { ardSoundsBridgeUrl(it, showId) }?.let { OpmlFeed(feed.title, it) }
            },
            title,
            createdAt,
        )

    /**
     * Übernimmt die Abos aus [feeds]. Abos, die es schon gibt (oder die in der Datei mehrfach stehen), werden übersprungen.
     * Mit [replace] fallen zuvor alle anderen Abos weg (samt Folgen und Hörfortschritt); Podcasts vom Gerät bleiben, denn
     * eine OPML-Datei kann sie nicht enthalten, ebenso ARD-Sounds-Abos, wenn die Datei keine enthält (ohne RSS-Bridge
     * exportiert sie EarLoft nicht). Eine Datei ohne Abos ersetzt nichts. Feeds der RSS-Bridge für ARD Sounds (und Links von Sendungsseiten) werden
     * wieder zu Abos `ardsounds:<Kennung>`, die ohne die Bridge auskommen. Die Folgen der neuen Abos kommen erst mit dem
     * nächsten Abgleich.
     */
    suspend fun importOpml(feeds: List<OpmlFeed>, replace: Boolean): OpmlImportResult = mutex.withLock {
        val wanted = feeds.map { feed -> parseArdSoundsInput(feed.url)?.let { feed.copy(url = it) } ?: feed }.distinctBy { it.url }
        var removed = 0
        if (replace && wanted.isNotEmpty()) {
            val keep = wanted.mapTo(HashSet()) { it.url }
            val fileHasArdSounds = wanted.any { ardSoundsShowId(it.url) != null }
            dao.feeds().filter { !it.isLocal && it.url !in keep && (fileHasArdSounds || ardSoundsShowId(it.url) == null) }.forEach {
                dao.deleteFeed(it.feedId)
                removed++
            }
        }
        val known = dao.feeds().mapTo(HashSet()) { it.url }
        val fresh = wanted.filter { it.url !in known }
        val autoDownload = settings.autoDownloadNew.first()
        fresh.forEach {
            // Ein Probeabo oder archiviertes Abo derselben Adresse wird (wieder) zum Abo (die Adresse ist eindeutig).
            // Nur das Probeabo bekommt die Vorgabe für neue Abos; das archivierte behält seine Einstellung.
            val stored = dao.feedByUrl(it.url)
            if (stored != null) {
                if (!stored.subscribed) dao.subscribeTrial(stored.feedId, autoDownload)
                dao.setArchived(stored.feedId, false)
            } else {
                dao.upsertFeed(
                    PodcastFeedEntity(feedId = UUID.randomUUID().toString(), url = it.url, title = it.title, autoDownload = autoDownload),
                )
            }
        }
        OpmlImportResult(added = fresh.size, skipped = feeds.size - fresh.size, removed = removed)
    }

    suspend fun setAutoDownload(feedId: String, enabled: Boolean) = dao.setAutoDownload(feedId, enabled)

    suspend fun setNewestFirst(feedId: String, newestFirst: Boolean) = dao.setNewestFirst(feedId, newestFirst)

    suspend fun setContinuous(feedId: String, continuous: Boolean) = dao.setContinuous(feedId, continuous)

    suspend fun setNotifyNew(feedId: String, enabled: Boolean) = dao.setNotifyNew(feedId, enabled)

    suspend fun setEpisodeList(feedId: String, filter: EpisodeFilter, oldestFirst: Boolean) =
        dao.setEpisodeList(feedId, filter, oldestFirst)

    suspend fun setQueueMode(feedId: String, mode: FeedQueueMode) = dao.setQueueMode(feedId, mode)

    suspend fun setKeepEpisodes(feedId: String, keep: Int) = dao.setKeepEpisodes(feedId, keep.coerceAtLeast(0))

    /** `null` = Vorgabe aus den Einstellungen. */
    suspend fun setAutoDownloadCount(feedId: String, count: Int?) = dao.setAutoDownloadCount(feedId, count?.coerceAtLeast(1))

    /**
     * Die ungehörte Folge, die in der Reihenfolge des Podcasts auf [bookId] folgt und in die Zeile „Weiterhören“
     * eingereiht wird. `null` bei Hörbüchern, wenn das Einreihen für den Podcast aus ist oder nichts Ungehörtes mehr folgt.
     */
    suspend fun autoNextAfter(bookId: Long): Long? {
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.EPISODE } ?: return null
        // Probeabos reihen nichts von selbst ein: in der Warteschlange steht nur, was ausdrücklich gespielt oder geladen wurde.
        val feed = dao.feed(book.serverId)?.takeIf { it.continuous && it.subscribed } ?: return null
        return nextEpisode(playOrder(feed), dao.finishedEpisodeIds(feed.feedId).toSet(), bookId)
    }

    private suspend fun playOrder(feed: PodcastFeedEntity): List<Long> =
        dao.episodeIdsOldestFirst(feed.feedId).let { if (feed.newestFirst) it.asReversed() else it }

    /**
     * Gleicht alle Abos ab. Die Folgen eines Abos, das zum ersten Mal abgeglichen wird, zählen nicht als neu
     * (sonst meldete jedes frische Abo seine ganze Folgenliste).
     */
    suspend fun refreshAll(): PodcastRefreshSummary {
        val failures = mutableMapOf<String, FeedError>()
        val news = mutableListOf<NewEpisodes>()
        val moved = mutableListOf<String>()
        val feeds = dao.feeds().filter { !it.isLocal }
        feeds.forEach { feed ->
            when (val outcome = mutex.withLock { refreshLocked(feed.feedId) }) {
                is RefreshOutcome.Failed -> failures[feed.feedId] = outcome.error
                is RefreshOutcome.Done -> {
                    val ids = outcome.result.newEpisodeIds
                    if (feed.lastRefreshAt > 0 && ids.isNotEmpty()) {
                        news += NewEpisodes(feed.feedId, feed.title, ids, feed.notifyNew, feed.autoDownload)
                    }
                    if (follow(feed.feedId, outcome.movedTo)) moved += feed.title
                }
            }
        }
        val allFailed = failures.isNotEmpty() && failures.size == feeds.size
        // Ist kein Feed erreichbar, liegt es am Netz, nicht an den Abos: Das zählt nicht als ihr Fehlschlag.
        val now = System.currentTimeMillis()
        failures.filterValues { !allFailed || it != FeedError.UNREACHABLE }
            .forEach { (feedId, error) -> dao.recordRefreshFailure(feedId, error, now) }
        return PodcastRefreshSummary(ok = failures.isEmpty(), newEpisodes = news, allFailed = allFailed, moved = moved)
    }

    /**
     * Gleicht einen Feed ab. `null`, wenn er nicht erreichbar oder unlesbar ist (die alten Folgen bleiben, der Fehlschlag
     * zählt für den Fehlerstatus des Abos). Kündigt der Feed eine neue Adresse an, zieht das Abo danach dorthin um.
     */
    suspend fun refresh(feedId: String): RefreshResult? =
        when (val outcome = mutex.withLock { refreshLocked(feedId) }) {
            is RefreshOutcome.Failed -> {
                dao.recordRefreshFailure(feedId, outcome.error, System.currentTimeMillis())
                null
            }
            is RefreshOutcome.Done -> outcome.result.also { follow(feedId, outcome.movedTo) }
        }

    /** Ergebnis eines Abrufs: übernommen (mit angekündigter neuer Adresse) oder gescheitert. */
    private sealed interface RefreshOutcome {
        data class Done(val result: RefreshResult, val movedTo: String? = null) : RefreshOutcome
        data class Failed(val error: FeedError) : RefreshOutcome
    }

    private suspend fun refreshLocked(feedId: String): RefreshOutcome {
        val feed = dao.feed(feedId) ?: return RefreshOutcome.Done(RefreshResult(emptyList()))
        // Podcasts vom Gerät haben keinen Feed; ihre Folgen gleicht [LocalPodcasts] ab.
        if (feed.isLocal) return RefreshOutcome.Done(RefreshResult(emptyList()))
        val fetched = try {
            fetch(feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Abgleich von ${feed.title} fehlgeschlagen", e)
            return RefreshOutcome.Failed(feedError(e))
        }
        val result = when (fetched) {
            is FeedFetch.NotModified -> {
                dao.upsertFeed(feed.copy(lastRefreshAt = System.currentTimeMillis()).refreshSucceeded())
                RefreshResult(emptyList())
            }
            is FeedFetch.Fetched -> apply(feed, fetched)
        }
        _refreshed.tryEmit(feedId)
        val movedTo = fetched.movedTo ?: (fetched as? FeedFetch.Fetched)?.feed?.newFeedUrl
        return RefreshOutcome.Done(result, movedTo?.takeIf { it != feed.url })
    }

    /** Zieht das Abo an die neue Adresse [movedTo] um (Konzept 12.10 „Feed-Umzug“); `true`, wenn es umgezogen ist. */
    private suspend fun follow(feedId: String, movedTo: String?): Boolean =
        movedTo != null && sourceSwitch.get().follow(feedId, movedTo)

    /** Sendungen aus ARD Sounds kommen über GraphQL, ohne bedingten Abruf; ein Fehler dort trifft nur sie. */
    private suspend fun fetch(feed: PodcastFeedEntity): FeedFetch {
        ardSoundsShowId(feed.url)?.let { return FeedFetch.Fetched(ardSounds.fetch(it), etag = null, lastModified = null) }
        return api.fetch(feed.url.toHttpUrlOrThrow(), feed.etag, feed.lastModified, credentials.forFeed(feed.feedId))
    }

    /**
     * Führt [block] aus, während kein Abgleich läuft (Quelle wechseln, Konzept 12.10): Ein Abgleich der alten Quelle
     * dazwischen brächte die alten Kennungen als neue Folgen zurück.
     */
    internal suspend fun <T> withFeedLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** Übernimmt [fetched] für [feed]; nur innerhalb von [withFeedLock]. */
    internal suspend fun applyLocked(feed: PodcastFeedEntity, fetched: FeedFetch.Fetched): RefreshResult =
        apply(feed, fetched).also { _refreshed.tryEmit(feed.feedId) }

    /**
     * In einer Transaktion: Eine ARD-Sendung bringt Hunderte Folgen; einzeln geschrieben wurde jede Folge ein eigener Commit und
     * eine eigene Benachrichtigung der Oberfläche (am Emulator 5 s für 352 Folgen, jetzt 0,3 s).
     */
    private suspend fun apply(feed: PodcastFeedEntity, fetched: FeedFetch.Fetched): RefreshResult = database.withTransaction {
        val parsed = fetched.feed
        val generation = feed.syncGeneration + 1
        val known = bookDao.booksOf(feed.feedId).associateBy { it.remoteId }
        val title = parsed.title.ifBlank { feed.title }
        // Manche Feeds nennen kein Bild (oder ein leeres, etwa die der rbb-Sender): Dann bleibt das bekannte – auch das aus
        // dem Verzeichnis –, sonst das der neuesten Folge.
        val image = parsed.imageUrl ?: feed.imageUrl ?: parsed.episodes.firstNotNullOfOrNull { it.imageUrl }
        val now = System.currentTimeMillis()
        val newIds = mutableListOf<Long>()
        val knownPersons = bookDao.personsOf(feed.feedId).groupBy { it.bookId }
        // Der Feed führt die neueste Folge zuerst; die erste Folge eines doppelten guid gewinnt.
        parsed.episodes.distinctBy { it.guid }.forEach { episode ->
            val existing = known[episode.guid]
            val id = bookDao.upsertBook(episode.toEntity(feed.feedId, title, image, existing, generation, now))
            if (existing == null) newIds += id
            // Mitwirkende kommen mit jedem Abgleich wieder; geschrieben wird nur, was sich geändert hat.
            val persons = episode.persons.mapIndexed { index, p -> EpisodePersonEntity(id, index, p.name, p.role, p.group, p.imageUrl, p.url) }
            if (knownPersons[id].orEmpty() != persons) bookDao.replacePersons(id, persons)
            val track = BookTrackEntity(id, 0, episode.audioUrl, 0, episode.durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0, episode.mimeType)
            // Der Download-Stand gehört nicht zum Feed und darf keinen Schreibvorgang auslösen.
            val stored = bookDao.tracks(id).map { it.copy(downloadState = TrackDownload.NONE, filePath = null, fileSize = 0) }
            if (stored != listOf(track)) dao.setTrack(track)
            if (episode.chapters.isNotEmpty()) storeChapters(id, episode.chapters, ChapterSource.FEED, track.durationMs, existing?.chapterSource)
        }
        dao.deleteUnseenEpisodes(feed.feedId, generation)
        dao.upsertFeed(
            feed.copy(
                title = title,
                author = parsed.author,
                description = parsed.description.toDescriptionHtml(),
                imageUrl = image,
                websiteUrl = parsed.websiteUrl,
                podcastGuid = parsed.podcastGuid,
                fundingUrl = parsed.fundingUrl,
                fundingLabel = parsed.fundingLabel.takeIf { parsed.fundingUrl != null },
                etag = fetched.etag,
                lastModified = fetched.lastModified,
                lastRefreshAt = now,
                syncGeneration = generation,
            ).refreshSucceeded(),
        )
        RefreshResult(newIds)
    }

    /**
     * Vor dem Abspielen (Konzept 12.5 „Kapitelquellen“): lädt die Kapitelliste (JSON) der Folge, falls der Feed eine anbietet
     * und sie noch nicht geladen ist – sie geht Kapiteln aus dem Feed und aus der Datei vor. Hat die Folge danach gar keine
     * Quelle, kommen sie aus der geladenen Datei ([chaptersFromFile]). Fehler sind unkritisch.
     */
    suspend fun ensureChapters(bookId: Long) {
        val book = bookDao.book(bookId) ?: return
        if (book.kind != BookKind.EPISODE) return
        val url = book.chaptersUrl
        if (url != null && book.chapterSource != ChapterSource.JSON) {
            val chapters = try {
                api.chapters(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Kapitel von ${book.title} nicht abrufbar", e)
                emptyList()
            }
            if (chapters.isNotEmpty()) {
                storeChapters(bookId, chapters, ChapterSource.JSON, book.durationMs, book.chapterSource)
                return
            }
        }
        if (book.chapterSource == null) chaptersFromFile(bookId)
    }

    /**
     * Kapitel aus der geladenen Datei der Folge (ID3 `CHAP`, MP4), wenn sie noch keine aus einer anderen Quelle hat; nach dem
     * Download und vor dem Abspielen. Auch eine Datei ohne Kapitel wird vermerkt, damit sie nicht jedes Mal gelesen wird.
     */
    suspend fun chaptersFromFile(bookId: Long) {
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.EPISODE && it.chapterSource == null } ?: return
        // Kapitel ohne vermerkte Quelle stammen aus einer Kapitelliste (vor Schema v29, oder v30 zum Neuladen); sie bleiben.
        if (bookDao.chapters(bookId).isNotEmpty()) return
        val track = bookDao.tracks(bookId).firstOrNull { it.downloadState == TrackDownload.DONE } ?: return
        val file = track.filePath?.let(::File)?.takeIf { it.isFile } ?: return
        val chapters = fileChapters.read(file, track.mimeType, book.durationMs)
        // Inzwischen kann eine höhere Quelle geschrieben haben (Abgleich, JSON); dann bleibt deren Liste.
        if (bookDao.chapterSource(bookId) != null) return
        bookDao.replaceChapters(
            bookId,
            chapters.mapIndexed { index, chapter -> BookChapterEntity(bookId, index, chapter.title, chapter.startMs, chapter.endMs) },
            ChapterSource.FILE,
        )
    }

    /**
     * Schreibt Kapitel aus [source], sofern die bisherigen ([current]) keinen höheren Rang haben; gleiche Kapitel werden nicht
     * neu geschrieben (der Feed bringt sie bei jedem Abgleich wieder). Jedes Kapitel endet am Beginn des nächsten, das letzte
     * am Ende der Folge.
     */
    private suspend fun storeChapters(bookId: Long, chapters: List<ParsedChapter>, source: ChapterSource, durationMs: Long, current: ChapterSource?) {
        if (current != null && current > source) return
        val lastStart = chapters.last().startMs
        val end = durationMs.takeIf { it > lastStart } ?: (lastStart + 1)
        val entities = chapters.mapIndexed { index, chapter ->
            BookChapterEntity(
                bookId,
                index,
                chapter.title.ifBlank { "${index + 1}" },
                chapter.startMs,
                chapters.getOrNull(index + 1)?.startMs ?: end,
                imageUrl = chapter.imageUrl,
                url = chapter.url,
            )
        }
        if (current == source && bookDao.chapters(bookId) == entities) return
        bookDao.replaceChapters(bookId, entities, source)
    }

    private fun ParsedEpisode.toEntity(
        feedId: String,
        feedTitle: String,
        feedImage: String?,
        existing: BookEntity?,
        generation: Long,
        now: Long,
    ) = BookEntity(
        serverId = feedId,
        kind = BookKind.EPISODE,
        remoteId = guid,
        title = title.ifBlank { guid },
        normTitle = title.normalizeForMerge(),
        author = feedTitle,
        description = description.toDescriptionHtml(),
        durationMs = durationMs.takeIf { it > 0 } ?: existing?.durationMs ?: 0,
        // Ohne Datum im Feed bleibt der Zeitpunkt, an dem die Folge zuerst aufgetaucht ist.
        addedAt = publishedAt.takeIf { it > 0 } ?: existing?.addedAt ?: now,
        // Dateien kommen aus dem Feed; es gibt keinen Server, der sie nachliefern müsste.
        detailsLoaded = true,
        seenInGeneration = generation,
        coverUrl = imageUrl ?: feedImage,
        chaptersUrl = chaptersUrl,
        webUrl = webUrl,
        chapterSource = existing?.chapterSource,
        season = season,
        episodeNumber = episodeNumber,
        episodeType = episodeType,
        transcriptUrl = transcriptUrl,
        transcriptType = transcriptType,
    )

    private fun String.toHttpUrlOrThrow() = normalizeFeedUrl(this) ?: throw IOException("Ungültige Feed-Adresse: $this")

    private companion object {
        const val TAG = "PodcastRepository"
        const val NEW_DAYS = 14L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_NEW = 20

        /** Wie bei den Büchern (`BookRepository.recentlyAdded`). */
        const val RECENT_LIMIT = 12

        /** Länge des Verlaufs: genug zum Wiederfinden, ohne jede je gehörte Folge zu laden. */
        const val HISTORY_LIMIT = 200

        /** SQLite-Grenze für Parameter je Abfrage, mit Abstand. */
        const val MAX_ARGS = 500
    }
}

private val PodcastFeedEntity.isLocal: Boolean get() = feedId.startsWith(LOCAL_PODCAST_PREFIX)

/** Ein Abo mit RSS-/Atom-Feed, also weder vom Gerät noch aus ARD Sounds; nur solche kennen OPML und andere Apps. */
internal val PodcastFeedEntity.hasRssFeed: Boolean get() = !isLocal && ardSoundsShowId(url) == null

private fun PodcastFeedEntity.toModel() = PodcastFeed(
    id = feedId,
    url = url,
    title = title,
    author = author,
    description = description,
    imageUrl = imageUrl,
    autoDownload = autoDownload,
    lastRefreshAt = lastRefreshAt,
    newestFirst = newestFirst,
    continuous = continuous,
    notifyNew = notifyNew,
    subscribed = subscribed,
    archived = archived,
    effects = EffectOverrides(speed, skipSilence, volumeBoostDb),
    skipIntroS = skipIntroS,
    skipOutroS = skipOutroS,
    episodeFilter = episodeFilter,
    listOldestFirst = listOldestFirst,
    queueMode = queueMode,
    keepEpisodes = keepEpisodes,
    autoDownloadCount = autoDownloadCount,
    failedRefreshes = failedRefreshes,
    refreshError = lastError,
    failingSince = failingSince,
    fundingUrl = fundingUrl,
    fundingLabel = fundingLabel.orEmpty(),
)

/** Ein gelungener Abgleich beendet die Serie der Fehlschläge. */
private fun PodcastFeedEntity.refreshSucceeded() = copy(failedRefreshes = 0, lastError = null, failingSince = 0)

/** Grund eines gescheiterten Abrufs für den Fehlerstatus des Abos. */
internal fun feedError(e: IOException): FeedError = when {
    e is PodcastFeedException -> FeedError.NOT_A_FEED
    e is HttpStatusException && (e.status == 404 || e.status == 410) -> FeedError.GONE
    e is HttpStatusException && (e.status == 401 || e.status == 403) -> FeedError.DENIED
    e is HttpStatusException -> FeedError.SERVER
    e is SSLException -> FeedError.TLS
    else -> FeedError.UNREACHABLE
}

/**
 * Trailer (`itunes:episodeType`) fallen weg, sobald ihr Abo in der Liste echte Folgen hat (Konzept 12.5 „Podcasting 2.0“);
 * angefangene, gehörte und gemerkte bleiben.
 */
internal fun List<BookEntity>.withoutTrailers(byBook: Map<Long, BookProgressEntity>): List<BookEntity> {
    if (none { it.episodeType == EpisodeType.TRAILER }) return this
    val withEpisodes = filter { it.episodeType != EpisodeType.TRAILER }.mapTo(HashSet()) { it.serverId }
    return filter { it.episodeType != EpisodeType.TRAILER || it.serverId !in withEpisodes || !byBook[it.bookId].isUntouched || it.starredAt != null }
}

/** Nie angefangen und nicht als gehört markiert. */
private val BookProgressEntity?.isUntouched: Boolean get() = this == null || (!finished && positionMs <= 0)

/** Folgen (neueste zuerst) ohne die, die „Folgen behalten“ ihres Abos ausblendet. */
private fun List<BookEntity>.visibleIn(feeds: List<PodcastFeedEntity>, byBook: Map<Long, BookProgressEntity>): List<BookEntity> {
    val keep = feeds.filter { it.keepEpisodes > 0 }.associate { it.feedId to it.keepEpisodes }
    val shown = withoutTrailers(byBook)
    if (keep.isEmpty()) return shown
    return shown.keepNewest({ it.serverId }, { keep[it] ?: 0 }) { byBook[it.bookId].isUntouched && it.starredAt == null }
}

/**
 * Beschreibungen kommen als HTML und bleiben mit ihren Tags erhalten; die Oberfläche setzt sie um. Manche Feeds maskieren
 * das HTML doppelt (nach dem Einlesen steht `&lt;p&gt;` statt `<p>` da): Das wird hier einmal aufgelöst.
 */
internal fun String.toDescriptionHtml(): String {
    val text = trim()
    return if ('<' !in text && text.contains("&lt;")) Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT).toString().trim() else text
}
