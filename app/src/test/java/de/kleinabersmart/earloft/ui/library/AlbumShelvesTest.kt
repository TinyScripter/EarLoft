package de.kleinabersmart.earloft.ui.library

import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.ColorFamily
import org.junit.Assert.assertEquals
import org.junit.Test
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
    fun `recently added by month, recently played by how long ago`() {
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
                album(2, lastPlayedAt = daysAgo(6)),
                album(3, lastPlayedAt = daysAgo(20)),
                album(4, lastPlayedAt = daysAgo(200)),
                album(5, lastPlayedAt = daysAgo(400)),
                album(6),
            ),
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
