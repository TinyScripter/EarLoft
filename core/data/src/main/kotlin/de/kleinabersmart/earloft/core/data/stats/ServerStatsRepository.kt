package de.kleinabersmart.earloft.core.data.stats

import android.util.Log
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.stats.StatsRepository.Companion.inYear
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DayListeningRow
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.entity.ServerStatsCacheEntity
import de.kleinabersmart.earloft.core.model.BookListening
import de.kleinabersmart.earloft.core.model.ServerBookStats
import de.kleinabersmart.earloft.core.model.WeekdayListening
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsListeningStats
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Die Hörstatistik der Audiobookshelf-Konten (Konzept 12.2, Statistik): vom Server abrufen, unverändert zwischenspeichern
 * (die Seite zeigt offline den letzten Stand) und für die Oberfläche aufbereiten. Getrennt von den Zahlen der App
 * (`listening_stats`): das sind die des Servers.
 */
@Singleton
class ServerStatsRepository @Inject constructor(
    private val statsDao: StatsDao,
    private val bookDao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
) {
    /** Letzter Stand des Servers; `null`, solange noch nie abgerufen. */
    fun stats(serverId: String, year: Int? = null, today: () -> LocalDate = LocalDate::now): Flow<ServerBookStats?> =
        statsDao.observeServerStats(serverId, KIND).map { cache ->
            cache?.let {
                try {
                    toStats(serverId, AbsStats.parse(it.json), it.fetchedAt, today(), year)
                } catch (e: IOException) {
                    Log.w(TAG, "Zwischengespeicherte Statistik von $serverId unlesbar", e)
                    null
                }
            }
        }

    /** Ruft die Statistik des Servers ab, wenn der Stand älter als [maxAgeMs] ist (eigene Sitzungen sollen bald sichtbar werden). `true`, wenn der Stand aktuell ist. */
    suspend fun refresh(serverId: String, maxAgeMs: Long = STALE_MS, now: Long = System.currentTimeMillis()): Boolean {
        val cached = statsDao.serverStats(serverId, KIND)
        if (cached != null && now - cached.fetchedAt < maxAgeMs) return true
        val server = servers.server(serverId) ?: return false
        return try {
            statsDao.putServerStats(ServerStatsCacheEntity(serverId, KIND, api.listeningStats(server), now))
            true
        } catch (e: IOException) {
            Log.w(TAG, "Statistik von ${server.baseUrl} nicht abrufbar", e)
            false
        }
    }

    private suspend fun toStats(serverId: String, stats: AbsListeningStats, fetchedAt: Long, today: LocalDate, year: Int?): ServerBookStats {
        val days = stats.days.mapNotNull { (key, seconds) ->
            try {
                DayListeningRow(LocalDate.parse(key).toEpochDay(), (seconds * 1000).roundToLong())
            } catch (e: DateTimeParseException) {
                null
            }
        }
        val yearDays = if (year == null) days else days.inYear(year)
        val weekdays = if (year == null) {
            stats.dayOfWeek.mapNotNull { (name, seconds) ->
                runCatching { DayOfWeek.valueOf(name.trim().uppercase()) }.getOrNull()?.let { it to (seconds * 1000).roundToLong() }
            }.toMap()
        } else {
            yearDays.groupBy { LocalDate.ofEpochDay(it.day).dayOfWeek }.mapValues { (_, rows) -> rows.sumOf { it.listenedMs } }
        }
        val books = if (year != null) emptyList() else stats.items.entries
            .map { (id, item) -> Triple(item.id.ifEmpty { id }, item, (item.timeListening * 1000).roundToLong()) }
            .filter { it.third > 0 }
            .sortedByDescending { it.third }
            .take(StatsRepository.TOP_BOOKS)
            .map { (id, item, ms) ->
                BookListening(id, item.mediaMetadata?.title.orEmpty(), ms, bookDao.bookByRemote(serverId, id)?.bookId)
            }
        return ServerBookStats(
            totalMs = if (year == null) (stats.totalTime * 1000).roundToLong() else yearDays.sumOf { it.listenedMs },
            todayMs = (stats.today * 1000).roundToLong(),
            streakDays = streak(days.filter { it.listenedMs > 0 }.mapTo(HashSet()) { it.day }, today),
            books = books,
            months = if (year == null) StatsRepository.monthsOf(days, today) else StatsRepository.monthsOfYear(days, year),
            weekdays = DayOfWeek.entries.map { WeekdayListening(it.value, weekdays[it] ?: 0) },
            fetchedAt = fetchedAt,
            years = StatsRepository.yearsOf(days, year),
            year = year,
        )
    }

    internal companion object {
        const val KIND = "listening"
        const val STALE_MS = 5 * 60_000L
        private const val TAG = "ServerStatsRepository"

        /** Tage in Folge bis heute; hat heute noch nichts gezählt, beginnt die Zählung gestern. */
        fun streak(listenedDays: Set<Long>, today: LocalDate): Int {
            var day = today.toEpochDay()
            if (day !in listenedDays) day--
            var count = 0
            while (day in listenedDays) {
                count++
                day--
            }
            return count
        }
    }
}
