package de.kleinabersmart.earloft.ui.library

import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.ColorFamily
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class AlbumShelvesTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 11, 24, 20, 0).atZone(zone).toInstant().toEpochMilli()

    private fun album(
        id: Long,
        title: String = "Album $id",
        artist: String = "Artist",
        year: Int? = null,
        addedAt: Long? = null,
        lastPlayedAt: Long? = null,
        seedColor: Int? = null,
    ) = Album(id, title, artist, year, 1, seedColor = seedColor, addedAt = addedAt, lastPlayedAt = lastPlayedAt)

    private fun daysAgo(days: Long) = now - days * 24 * 60 * 60 * 1000

    private fun shelves(sort: AlbumSort, vararg albums: Album) =
        albumShelves(albums.toList(), sort, now, zone).map { shelf -> shelf.label to shelf.albums.map { it.id } }

    private fun at(year: Int, month: Int, day: Int) = LocalDateTime.of(year, month, day, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun playedShelves(now: Long, firstDayOfWeek: DayOfWeek, vararg albums: Album) =
        albumShelves(albums.toList(), AlbumSort.RECENTLY_PLAYED, now, zone, firstDayOfWeek)
            .map { shelf -> (shelf.label as ShelfLabel.Played).bucket to shelf.albums.map { it.id } }

    @Test
    fun `title shelves by folded letter, a title sorted to the end joins its letter`() {
        assertEquals(
            listOf(ShelfLabel.Letter("#") to listOf(1L), ShelfLabel.Letter("A") to listOf(2L, 4L), ShelfLabel.Letter("B") to listOf(3L)),
            shelves(AlbumSort.TITLE, album(1, "1984"), album(2, "Abbey Road"), album(3, "Blue"), album(4, "Ärzte statt Böller")),
        )
    }

    @Test
    fun `artist shelves per artist, decades with albums without a year apart`() {
        assertEquals(
            listOf(ShelfLabel.ArtistName("ABBA") to listOf(1L, 2L), ShelfLabel.ArtistName("Blur") to listOf(3L)),
            shelves(AlbumSort.ARTIST, album(1, artist = "ABBA"), album(2, artist = "ABBA "), album(3, artist = "Blur")),
        )
        assertEquals(
            listOf(ShelfLabel.Decade(2020) to listOf(1L), ShelfLabel.Decade(1980) to listOf(2L, 3L), ShelfLabel.NoYear to listOf(4L, 5L)),
            shelves(AlbumSort.YEAR, album(1, year = 2022), album(2, year = 1989), album(3, year = 1980), album(4), album(5, year = 0)),
        )
    }

    @Test
    fun `recently added by month, recently played by calendar`() {
        assertEquals(
            listOf(ShelfLabel.Month(2026, 11) to listOf(1L, 2L), ShelfLabel.Month(2026, 10) to listOf(3L)),
            shelves(AlbumSort.RECENTLY_ADDED, album(1, addedAt = daysAgo(0)), album(2, addedAt = daysAgo(20)), album(3, addedAt = daysAgo(30))),
        )
        assertEquals(
            listOf(
                ShelfLabel.Played(PlayedBucket.TODAY) to listOf(1L),
                ShelfLabel.Played(PlayedBucket.WEEK) to listOf(2L),
                ShelfLabel.Played(PlayedBucket.MONTH) to listOf(3L),
                ShelfLabel.Played(PlayedBucket.YEAR) to listOf(4L),
                ShelfLabel.Played(PlayedBucket.EARLIER) to listOf(5L),
                ShelfLabel.Played(PlayedBucket.NEVER) to listOf(6L),
            ),
            shelves(
                AlbumSort.RECENTLY_PLAYED,
                album(1, lastPlayedAt = daysAgo(0)),
                album(2, lastPlayedAt = daysAgo(1)),
                album(3, lastPlayedAt = daysAgo(20)),
                album(4, lastPlayedAt = daysAgo(200)),
                album(5, lastPlayedAt = daysAgo(400)),
                album(6),
            ),
        )
    }

    @Test
    fun `played shelves follow the calendar, not rolling windows`() {
        // Tuesday, 24 November 2026; the week began on Monday the 23rd.
        assertEquals(
            listOf(
                PlayedBucket.TODAY to listOf(1L),
                PlayedBucket.WEEK to listOf(2L),
                PlayedBucket.MONTH to listOf(3L, 4L),
                PlayedBucket.YEAR to listOf(5L),
                PlayedBucket.EARLIER to listOf(6L),
            ),
            playedShelves(
                now,
                DayOfWeek.MONDAY,
                album(1, lastPlayedAt = at(2026, 11, 24)),
                album(2, lastPlayedAt = at(2026, 11, 23)),
                album(3, lastPlayedAt = at(2026, 11, 22)),
                album(4, lastPlayedAt = at(2026, 11, 1)),
                album(5, lastPlayedAt = at(2026, 10, 31)),
                // Less than a year ago, but last year.
                album(6, lastPlayedAt = at(2025, 12, 31)),
            ),
        )
    }

    @Test
    fun `the week starts on the region's first day`() {
        val sunday = at(2026, 11, 22)
        assertEquals(listOf(PlayedBucket.MONTH to listOf(1L)), playedShelves(now, DayOfWeek.MONDAY, album(1, lastPlayedAt = sunday)))
        assertEquals(listOf(PlayedBucket.WEEK to listOf(1L)), playedShelves(now, DayOfWeek.SUNDAY, album(1, lastPlayedAt = sunday)))
    }

    @Test
    fun `a week that began last month still counts as this week`() {
        // Tuesday, 1 December 2026; the week began on Monday, 30 November.
        val december = at(2026, 12, 1)
        assertEquals(
            listOf(PlayedBucket.WEEK to listOf(1L), PlayedBucket.YEAR to listOf(2L)),
            playedShelves(december, DayOfWeek.MONDAY, album(1, lastPlayedAt = at(2026, 11, 30)), album(2, lastPlayedAt = at(2026, 11, 29))),
        )
    }

    @Test
    fun `color shelves by family`() {
        assertEquals(
            listOf(ShelfLabel.Color(ColorFamily.RED) to listOf(1L), ShelfLabel.Color(ColorFamily.NONE) to listOf(2L)),
            shelves(AlbumSort.COLOR, album(1, seedColor = 0xFFE53935.toInt()), album(2)),
        )
    }
}
