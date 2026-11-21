package de.kleinabersmart.earloft.ui.spoken

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedAddressInputTest {

    @Test
    fun `addresses with a scheme or www subscribe`() {
        listOf(
            "https://example.com/feed.xml",
            "http://example.com/rss",
            "feed://example.com/rss",
            "feed:https://example.com/rss",
            "itpc://example.com/podcast.xml",
            "pcast://example.com/rss",
            "www.example.com/feed",
            "  https://example.com/feed.xml  ",
        ).forEach { assertTrue(it, looksLikeFeedAddress(it)) }
    }

    @Test
    fun `a host with a path is an address`() {
        assertTrue(looksLikeFeedAddress("example.com/feed.xml"))
        assertTrue(looksLikeFeedAddress("feeds.example.org/podcast"))
    }

    @Test
    fun `search terms stay search terms`() {
        listOf("", "   ", "history", "true crime", "example.com", "AC/DC", "Lage der Nation", "https://example.com mit Text")
            .forEach { assertFalse(it, looksLikeFeedAddress(it)) }
    }
}
