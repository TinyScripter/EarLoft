package de.kleinabersmart.earloft.ui.spoken

import org.junit.Assert.assertEquals
import org.junit.Test
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class EpisodeDateTest {

    private fun millis(year: Int, month: Int, day: Int) =
        Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0) }.timeInMillis

    @Test
    fun `the year is left out in the current year`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("Oct 31", formatEpisodeDate(millis(2026, Calendar.OCTOBER, 31), now, Locale.US))
    }

    @Test
    fun `the year is shown for other years`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("Oct 31, 2025", formatEpisodeDate(millis(2025, Calendar.OCTOBER, 31), now, Locale.US))
    }

    @Test
    fun `german months are abbreviated before the day`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("Okt. 31", formatEpisodeDate(millis(2026, Calendar.OCTOBER, 31), now, Locale.GERMANY))
    }

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    private fun episode(id: Long, feed: String, at: Long, title: String = "Folge $id") =
        Book(id = id, serverId = feed, kind = BookKind.EPISODE, title = title, author = "", durationMs = 0, remoteId = "$id", addedAt = at)

    private fun berlinMillis(day: Int, hour: Int) =
        Calendar.getInstance(berlin).apply { clear(); set(2026, Calendar.NOVEMBER, day, hour, 0) }.timeInMillis

    @Test
    fun `episodes of one feed on the same day get the time`() {
        val books = listOf(
            episode(1, "news", berlinMillis(12, 7)),
            episode(2, "news", berlinMillis(12, 17)),
            episode(3, "news", berlinMillis(11, 17)),
            // Anderer Podcast am selben Tag: kein Grund für die Uhrzeit.
            episode(4, "talk", berlinMillis(12, 9)),
            // Kurz nach Mitternacht ist schon der nächste Tag.
            episode(5, "talk", berlinMillis(13, 0)),
        )
        assertEquals(setOf(1L, 2L), episodesSharingDay(books, berlin))
    }

    @Test
    fun `books and episodes without date never share a day`() {
        val books = listOf(
            episode(1, "news", 0),
            episode(2, "news", 0),
            episode(3, "abs", berlinMillis(12, 7)).copy(kind = BookKind.BOOK),
            episode(4, "abs", berlinMillis(12, 8)).copy(kind = BookKind.BOOK),
        )
        assertEquals(emptySet<Long>(), episodesSharingDay(books, berlin))
    }

    @Test
    fun `equal titles within a feed are found regardless of case`() {
        val books = listOf(
            episode(1, "news", 1, "tagesschau in 100 Sekunden"),
            episode(2, "news", 2, "Tagesschau in 100 Sekunden "),
            episode(3, "news", 3, "Sondersendung"),
            episode(4, "other", 4, "Sondersendung"),
        )
        assertEquals(setOf(1L, 2L), episodesSharingTitle(books))
    }

    @Test
    fun `the notes line skips a repeated title`() {
        assertEquals("Themen: Bahnstreik, Wetter", firstLineOtherThan("\n tagesschau in 100 Sekunden\nThemen: Bahnstreik, Wetter\n", "Tagesschau in 100 Sekunden"))
        assertEquals("", firstLineOtherThan("tagesschau in 100 Sekunden", "tagesschau in 100 Sekunden"))
    }
}
