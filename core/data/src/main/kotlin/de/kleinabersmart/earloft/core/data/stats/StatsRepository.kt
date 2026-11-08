package de.kleinabersmart.earloft.core.data.stats

import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DayListeningRow
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookListening
import de.kleinabersmart.earloft.core.model.DeviceBookStats
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.earloft.core.model.MonthListening
import de.kleinabersmart.earloft.core.model.PodcastListening
import de.kleinabersmart.earloft.core.model.PodcastStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Gehörte Zeit: mitzählen und für die Statistik-Seite aufbereiten (Konzept 12.2, Statistik). */
@Singleton
class StatsRepository @Inject constructor(
    private val statsDao: StatsDao,
    private val bookDao: BookDao,
    private val podcastDao: PodcastDao,
) {
    /** Zählt [ms] gehörte Inhaltszeit der Folge [bookId] zu ihrer Summe und zum Tag von [now] hinzu. */
    suspend fun recordEpisodeListening(
        bookId: Long,
        ms: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (ms <= 0) return
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.EPISODE } ?: return
        // Probeabos aus der Vorschau zählen nicht (Konzept 12.10).
        val feed = podcastDao.feed(book.serverId)?.takeIf { it.subscribed } ?: return
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        statsDao.addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP,
                kind = BookKind.EPISODE,
                groupKey = feed.url,
                groupTitle = feed.title,
                itemKey = book.remoteId,
                itemTitle = book.title,
                listenedMs = ms,
                lastListenedAt = now,
            ),
            day,
        )
    }

    /** Wie [recordEpisodeListening], für ein Buch. Die Gruppe ist der Server (bzw. das Gerät), der Eintrag das Buch. */
    suspend fun recordBookListening(
        bookId: Long,
        ms: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (ms <= 0) return
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.BOOK } ?: return
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        statsDao.addListening(
            ListeningStatEntity(
                source = ListeningStatEntity.SOURCE_APP,
                kind = BookKind.BOOK,
                groupKey = book.serverId,
                groupTitle = bookDao.server(book.serverId)?.name.orEmpty(),
                itemKey = book.remoteId,
                itemTitle = book.title,
                listenedMs = ms,
                lastListenedAt = now,
            ),
            day,
        )
    }

    /**
     * Die vom Gerät gezählte Hörzeit der Bücher. Mit [year] nur die dieses Jahres: Das kennen nur die Tageswerte, also ist
     * nur selbst gezählte Zeit enthalten und es gibt keine „Meistgehört“-Liste.
     */
    fun bookStats(
        year: Int? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        today: () -> LocalDate = LocalDate::now,
    ): Flow<DeviceBookStats> {
        val (fromMs, toMs) = millisOf(year, zone)
        return combine(
            statsDao.observeTotalListenedMs(BookKind.BOOK),
            statsDao.observeTopItems(BookKind.BOOK, TOP_BOOKS),
            statsDao.observeFinishedBetween(BookKind.BOOK, fromMs, toMs),
            statsDao.observeDays(BookKind.BOOK, 0),
        ) { total, items, finished, days ->
            DeviceBookStats(
                totalMs = if (year == null) total else days.inYear(year).sumOf { it.listenedMs },
                finishedBooks = finished,
                books = if (year != null) emptyList() else items.map {
                    BookListening("${it.groupKey}/${it.itemKey}", it.itemTitle, it.listenedMs, bookDao.bookByRemote(it.groupKey, it.itemKey)?.bookId)
                },
                months = if (year == null) monthsOf(days, today()) else monthsOfYear(days, year),
                years = yearsOf(days, year),
                year = year,
            )
        }
    }

    /** Löscht die vom Gerät gezählte und importierte Hörzeit der Bücher. Die Zahlen des Servers bleiben. */
    suspend fun resetBookStats() = statsDao.clear(BookKind.BOOK)

    /** Wie [bookStats], für Podcasts; mit [year] ist die Liste je Podcast aus den Tageswerten und ohne Folgenzahl. */
    fun podcastStats(
        year: Int? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        today: () -> LocalDate = LocalDate::now,
    ): Flow<PodcastStats> {
        val (fromMs, toMs) = millisOf(year, zone)
        val fromDay = year?.let { LocalDate.of(it, 1, 1).toEpochDay() } ?: Long.MIN_VALUE
        val toDay = year?.let { LocalDate.of(it, 12, 31).toEpochDay() } ?: Long.MAX_VALUE
        return combine(
            statsDao.observeTotalListenedMs(BookKind.EPISODE),
            statsDao.observeByGroup(BookKind.EPISODE),
            statsDao.observeFinishedBetween(BookKind.EPISODE, fromMs, toMs),
            combine(
                podcastDao.observeFeeds(),
                podcastDao.observeArchivedFeeds(),
                statsDao.observeGroupDays(BookKind.EPISODE, fromDay, toDay),
                ::Triple,
            ),
            statsDao.observeDays(BookKind.EPISODE, 0),
        ) { total, groups, finished, (feeds, archived, groupDays), days ->
            val subscribed = feeds.filter { !it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }
            val feedByUrl = feeds.associateBy { it.url }
            val archivedUrls = archived.mapTo(HashSet()) { it.url }
            val titles = groups.associate { it.groupKey to it.groupTitle }
            PodcastStats(
                totalMs = if (year == null) total else days.inYear(year).sumOf { it.listenedMs },
                finishedEpisodes = finished,
                subscriptions = subscribed.size,
                podcasts = if (year == null) {
                    groups.map {
                        PodcastListening(
                            it.groupKey, it.groupTitle, it.listenedMs, it.items, feedByUrl[it.groupKey]?.feedId, it.groupKey in archivedUrls,
                        )
                    }
                } else {
                    groupDays.filter { it.listenedMs > 0 }.map {
                        PodcastListening(
                            it.groupKey,
                            titles[it.groupKey] ?: it.groupKey,
                            it.listenedMs,
                            0,
                            feedByUrl[it.groupKey]?.feedId,
                            it.groupKey in archivedUrls,
                        )
                    }
                },
                months = if (year == null) monthsOf(days, today()) else monthsOfYear(days, year),
                years = yearsOf(days, year),
                year = year,
            )
        }
    }

    /** Löscht alle Zahlen der Podcasts: die gezählten und die importierten. */
    suspend fun resetPodcastStats() = statsDao.clear(BookKind.EPISODE)

    internal companion object {
        const val MONTHS = 12
        const val TOP_BOOKS = 10

        /** Unix-ms-Grenzen (von einschließlich, bis ausschließlich) des Jahres [year]; ohne Jahr alles. */
        fun millisOf(year: Int?, zone: ZoneId): Pair<Long, Long> =
            if (year == null) {
                Long.MIN_VALUE to Long.MAX_VALUE
            } else {
                LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() to
                    LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            }

        /** Die Tageswerte aus dem Jahr [year]. */
        fun List<DayListeningRow>.inYear(year: Int): List<DayListeningRow> =
            filter { LocalDate.ofEpochDay(it.day).year == year }

        /** Die Jahre mit Tageswerten, das neueste zuerst; das gewählte [selected] ist immer dabei. */
        fun yearsOf(days: List<DayListeningRow>, selected: Int?): List<Int> =
            (days.filter { it.listenedMs > 0 }.map { LocalDate.ofEpochDay(it.day).year } + listOfNotNull(selected))
                .distinct().sortedDescending()

        /** Januar bis Dezember des Jahres [year], ohne Lücken. */
        fun monthsOfYear(days: List<DayListeningRow>, year: Int): List<MonthListening> {
            val sums = days.inYear(year).groupBy { LocalDate.ofEpochDay(it.day).monthValue }
                .mapValues { (_, rows) -> rows.sumOf { it.listenedMs } }
            return (1..12).map { MonthListening(year, it, sums[it] ?: 0) }
        }

        /** Die letzten [MONTHS] Monate bis einschließlich dem von [today], älteste zuerst, ohne Lücken. */
        fun monthsOf(days: List<DayListeningRow>, today: LocalDate): List<MonthListening> {
            val sums = days.groupBy { LocalDate.ofEpochDay(it.day).let { d -> d.year * 12 + d.monthValue - 1 } }
                .mapValues { (_, rows) -> rows.sumOf { it.listenedMs } }
            return (MONTHS - 1 downTo 0).map { back ->
                val month = today.withDayOfMonth(1).minusMonths(back.toLong())
                MonthListening(month.year, month.monthValue, sums[month.year * 12 + month.monthValue - 1] ?: 0)
            }
        }
    }
}
