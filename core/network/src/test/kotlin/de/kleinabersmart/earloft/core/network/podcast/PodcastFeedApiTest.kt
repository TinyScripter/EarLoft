package de.kleinabersmart.earloft.core.network.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class PodcastFeedApiTest {

    @Test
    fun `feed urls are completed and schemes mapped`() {
        assertEquals("https://example.com/feed.xml", normalizeFeedUrl(" example.com/feed.xml ")?.toString())
        assertEquals("https://example.com/rss", normalizeFeedUrl("feed://example.com/rss")?.toString())
        assertEquals("https://example.com/rss", normalizeFeedUrl("itpc://example.com/rss")?.toString())
        assertEquals("http://example.com/rss", normalizeFeedUrl("http://example.com/rss")?.toString())
    }

    @Test
    fun `invalid feed urls are rejected`() {
        assertNull(normalizeFeedUrl(""))
        assertNull(normalizeFeedUrl("kein feed"))
        assertNull(normalizeFeedUrl("ftp://example.com/rss"))
        assertNull(normalizeFeedUrl("hallo"))
    }

    @Test
    fun `chapters are sorted and fractional starts kept`() {
        val chapters = parseChapters(
            """{"version":"1.2.0","chapters":[
                {"startTime":61.5,"title":" Zwei "},
                {"startTime":0,"title":"Eins","img":"https://example.com/1.jpg","url":"https://example.com/eins"},
                {"startTime":30,"title":"Relativ","img":"x.png","url":"/seite"},
                {"startTime":40,"endTime":45,"title":"Spende","img":"https://example.com/spende.png","toc":false},
                {"startTime":-3,"title":"kaputt"}
            ]}""",
        )
        assertEquals(
            listOf(
                ParsedChapter(0, "Eins", "https://example.com/1.jpg", "https://example.com/eins"),
                // Relative addresses are useless without a base; the silent chapter (toc: false) is missing.
                ParsedChapter(30_000, "Relativ"),
                ParsedChapter(61_500, "Zwei"),
            ),
            chapters,
        )
    }

    @Test
    fun `a file without chapters is empty and garbage is an io error`() {
        assertEquals(emptyList<ParsedChapter>(), parseChapters("""{"version":"1.2.0"}"""))
        assertThrows(IOException::class.java) { parseChapters("<html>") }
    }
}
