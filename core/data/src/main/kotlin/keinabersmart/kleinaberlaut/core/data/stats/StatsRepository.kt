package keinabersmart.kleinaberlaut.core.data.stats

import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.DayListeningRow
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.StatsDao
import keinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.LOCAL_PODCAST_PREFIX
import keinabersmart.kleinaberlaut.core.model.MonthListening
import keinabersmart.kleinaberlaut.core.model.PodcastListening
import keinabersmart.kleinaberlaut.core.model.PodcastStats
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
        val feed = podcastDao.feed(book.serverId) ?: return
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

    fun podcastStats(today: () -> LocalDate = LocalDate::now): Flow<PodcastStats> {
        val since = today().minusMonths(MONTHS - 1L).withDayOfMonth(1).toEpochDay()
        return combine(
            statsDao.observeTotalListenedMs(BookKind.EPISODE),
            statsDao.observeByGroup(BookKind.EPISODE),
            statsDao.observeFinishedEpisodes(),
            podcastDao.observeFeeds(),
            statsDao.observeDays(BookKind.EPISODE, since),
        ) { total, groups, finished, feeds, days ->
            val subscribed = feeds.filter { !it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }
            val feedByUrl = feeds.associateBy { it.url }
            PodcastStats(
                totalMs = total,
                finishedEpisodes = finished,
                subscriptions = subscribed.size,
                podcasts = groups.map {
                    PodcastListening(it.groupKey, it.groupTitle, it.listenedMs, it.items, feedByUrl[it.groupKey]?.feedId)
                },
                months = monthsOf(days, today()),
            )
        }
    }

    /** Löscht alle Zahlen der Podcasts: die gezählten und die importierten. */
    suspend fun resetPodcastStats() = statsDao.clear(BookKind.EPISODE)

    internal companion object {
        const val MONTHS = 12

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
