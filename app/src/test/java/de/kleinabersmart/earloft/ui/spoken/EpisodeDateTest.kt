package de.kleinabersmart.earloft.ui.spoken

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Robolectric for the ICU date patterns (`android.icu`). */
@RunWith(RobolectricTestRunner::class)
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
    fun `german puts the day before the month`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("31. Okt.", formatEpisodeDate(millis(2026, Calendar.OCTOBER, 31), now, Locale.GERMANY))
        assertEquals("31. Okt. 2025", formatEpisodeDate(millis(2025, Calendar.OCTOBER, 31), now, Locale.GERMANY))
    }

    @Test
    fun `british and french order follow their locale`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("31 Oct", formatEpisodeDate(millis(2026, Calendar.OCTOBER, 31), now, Locale.UK))
        assertEquals("31 oct.", formatEpisodeDate(millis(2026, Calendar.OCTOBER, 31), now, Locale.FRANCE))
    }

    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    private fun episode(id: Long, feed: String, at: Long, title: String = "Episode $id") =
        Book(id = id, serverId = feed, kind = BookKind.EPISODE, title = title, author = "", durationMs = 0, remoteId = "$id", addedAt = at)

    private fun berlinMillis(day: Int, hour: Int) =
        Calendar.getInstance(berlin).apply { clear(); set(2026, Calendar.NOVEMBER, day, hour, 0) }.timeInMillis

    @Test
    fun `episodes of one feed on the same day get the time`() {
        val books = listOf(
            episode(1, "news", berlinMillis(12, 7)),
            episode(2, "news", berlinMillis(12, 17)),
            episode(3, "news", berlinMillis(11, 17)),
            // Different podcast on the same day: no reason for the time of day.
            episode(4, "talk", berlinMillis(12, 9)),
            // Shortly after midnight it is already the next day.
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
    fun `the summary joins the lines into one paragraph without a repeated title`() {
        assertEquals(
            "Topics: rail strike, weather More at tagesschau.de",
            summaryOtherThan("tagesschau in 100 Sekunden\n\nTopics:  rail strike,\tweather\n More at tagesschau.de ", "Tagesschau in 100 Sekunden"),
        )
        assertEquals("", summaryOtherThan(" \n tagesschau in 100 Sekunden\n", "tagesschau in 100 Sekunden"))
    }
}
