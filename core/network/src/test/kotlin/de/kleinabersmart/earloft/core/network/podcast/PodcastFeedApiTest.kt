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
        assertNull(normalizeFeedUrl("not a feed"))
        assertNull(normalizeFeedUrl("ftp://example.com/rss"))
        assertNull(normalizeFeedUrl("hello"))
    }

    @Test
    fun `chapters are sorted and fractional starts kept`() {
        val chapters = parseChapters(
            """{"version":"1.2.0","chapters":[
                {"startTime":61.5,"title":" Two "},
                {"startTime":0,"title":"One","img":"https://example.com/1.jpg","url":"https://example.com/one"},
                {"startTime":30,"title":"Relative","img":"x.png","url":"/page"},
                {"startTime":40,"endTime":45,"title":"Donation","img":"https://example.com/donation.png","toc":false},
                {"startTime":-3,"title":"broken"}
            ]}""",
        )
        assertEquals(
            listOf(
                ParsedChapter(0, "One", "https://example.com/1.jpg", "https://example.com/one"),
                // Relative addresses are useless without a base; the silent chapter (toc: false) is missing.
                ParsedChapter(30_000, "Relative"),
                ParsedChapter(61_500, "Two"),
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
