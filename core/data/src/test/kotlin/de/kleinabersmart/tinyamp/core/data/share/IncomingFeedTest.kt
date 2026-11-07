package de.kleinabersmart.tinyamp.core.data.share

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingFeedTest {

    @Test
    fun `podcast app schemes become web addresses`() {
        assertEquals("https://example.com/rss", IncomingFeed.fromLink("feed://example.com/rss"))
        assertEquals("http://example.com/rss", IncomingFeed.fromLink("feed:http://example.com/rss"))
        assertEquals("https://example.com/rss", IncomingFeed.fromLink(" ITPC://example.com/rss "))
        assertEquals("https://example.com/rss", IncomingFeed.fromLink("pcast://example.com/rss"))
        assertEquals("https://example.com/rss", IncomingFeed.fromLink("podcast://example.com/rss"))
        assertEquals(null, IncomingFeed.fromLink("https://example.com/rss"))
        assertEquals(null, IncomingFeed.fromLink("feed:"))
    }

    @Test
    fun `text shared by tinyamp opens the feed, not the website`() {
        val text = "Folge\nPodcast\nhttps://example.com/folge-1\nRSS: https://example.com/feed.xml"
        assertEquals("https://example.com/feed.xml", IncomingFeed.fromText(text))
    }

    @Test
    fun `other text opens its first address`() {
        assertEquals("https://example.com/feed.xml", IncomingFeed.fromText("Hör mal (https://example.com/feed.xml)."))
        assertEquals("https://example.com/rss", IncomingFeed.fromText("Abo: feed://example.com/rss"))
        assertEquals(
            "https://www.ardsounds.de/sendung/x/urn:ard:show:1/",
            IncomingFeed.fromText("Talk\nhttps://www.ardsounds.de/sendung/x/urn:ard:show:1/"),
        )
        assertEquals(null, IncomingFeed.fromText("nur Text"))
    }
}
