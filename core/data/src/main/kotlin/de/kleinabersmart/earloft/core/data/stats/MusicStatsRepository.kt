package de.kleinabersmart.earloft.core.data.stats

import de.kleinabersmart.earloft.core.data.stats.StatsRepository.Companion.millisOf
import de.kleinabersmart.earloft.core.data.stats.StatsRepository.Companion.monthsOf
import de.kleinabersmart.earloft.core.data.stats.StatsRepository.Companion.monthsOfYear
import de.kleinabersmart.earloft.core.data.stats.StatsRepository.Companion.yearsOf
import de.kleinabersmart.earloft.core.database.dao.DayListeningRow
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.model.MusicPlayRow
import de.kleinabersmart.earloft.core.database.model.TopPlayedRow
import de.kleinabersmart.earloft.core.model.MusicStats
import de.kleinabersmart.earloft.core.model.MusicTopEntry
import de.kleinabersmart.earloft.core.model.WeekdayListening
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Statistik der Musik (Konzept 8 „Musik-Statistik“): rein aus dem Hörverlauf (`play_events`), also nur Wiedergaben, die
 * die App gezählt hat (Hälfte bzw. 30 s), auch die der Uhr. Songs ausgeschalteter Server zählen nicht mit.
 */
@Singleton
class MusicStatsRepository @Inject constructor(private val libraryDao: LibraryDao) {

    /** Mit [year] nur die Wiedergaben dieses Jahres, sonst alle; [MusicStats.months] wie bei [StatsRepository.podcastStats]. */
    fun musicStats(
        year: Int? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        today: () -> LocalDate = LocalDate::now,
    ): Flow<MusicStats> {
        val (fromMs, toMs) = millisOf(year, zone)
        return combine(
            libraryDao.observeMusicPlays(),
            libraryDao.observeTopArtists(fromMs, toMs, TOP),
            libraryDao.observeTopAlbums(fromMs, toMs, TOP),
            libraryDao.observeTopSongs(fromMs, toMs, TOP),
            libraryDao.observeTopGenres(fromMs, toMs, TOP),
        ) { plays, artists, albums, songs, genres ->
            val inRange = plays.filter { it.playedAt in fromMs until toMs }
            val days = plays.byDay(zone)
            val firstPlayOfAlbum = plays.groupBy { it.albumId }.mapValues { (_, rows) -> rows.minOf { it.playedAt } }
            MusicStats(
                totalMs = inRange.sumOf { it.listenedMs },
                plays = inRange.size,
                songs = inRange.distinctBy { it.songId }.size,
                albums = inRange.distinctBy { it.albumId }.size,
                newAlbums = firstPlayOfAlbum.values.count { it in fromMs until toMs },
                topArtists = artists.map { it.toEntry() },
                topAlbums = albums.map { it.toEntry() },
                topSongs = songs.map { it.toEntry() },
                topGenres = genres.map { it.toEntry() },
                months = if (year == null) monthsOf(days, today()) else monthsOfYear(days, year),
                weekdays = weekdaysOf(inRange, zone),
                years = yearsOf(days, year),
                year = year,
            )
        }
    }

    internal companion object {
        const val TOP = 10

        /** Gehörte Zeit je Kalendertag in [zone]. */
        fun List<MusicPlayRow>.byDay(zone: ZoneId): List<DayListeningRow> =
            groupBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).toLocalDate().toEpochDay() }
                .map { (day, rows) -> DayListeningRow(day, rows.sumOf { it.listenedMs }) }

        /** Gehörte Zeit je Wochentag, Montag zuerst, ohne Lücken. */
        fun weekdaysOf(plays: List<MusicPlayRow>, zone: ZoneId): List<WeekdayListening> {
            val sums = plays.groupBy { Instant.ofEpochMilli(it.playedAt).atZone(zone).dayOfWeek }
                .mapValues { (_, rows) -> rows.sumOf { it.listenedMs } }
            return DayOfWeek.entries.map { WeekdayListening(it.value, sums[it] ?: 0) }
        }

        private fun TopPlayedRow.toEntry() = MusicTopEntry(id, title, subtitle, albumId, plays, listenedMs)
    }
}
