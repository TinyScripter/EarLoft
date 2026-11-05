package de.kleinabersmart.tinyamp.core.network.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PodcastFeedParserTest {

    private fun parse(xml: String) = PodcastFeedParser.parse(xml.trimIndent().byteInputStream())

    @Test
    fun `rss with itunes and podcast namespace`() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:content="http://purl.org/rss/1.0/modules/content/"
                 xmlns:pc="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Der Podcast</title>
                <description>Kurz</description>
                <itunes:author>Radio Eins</itunes:author>
                <itunes:image href="https://example.com/cover.jpg"/>
                <image><url>https://example.com/alt.jpg</url></image>
                <item>
                  <title>Folge 2</title>
                  <guid isPermaLink="false">abc-2</guid>
                  <pubDate>Thu, 30 Oct 2025 06:00:00 +0100</pubDate>
                  <description>Kurztext</description>
                  <content:encoded><![CDATA[<p>Langtext</p>]]></content:encoded>
                  <enclosure url="https://example.com/2.mp3" length="123" type="audio/mpeg"/>
                  <itunes:duration>1:02:03</itunes:duration>
                  <itunes:image href="https://example.com/2.jpg"/>
                  <pc:chapters url="https://example.com/2.json" type="application/json+chapters"/>
                </item>
                <item>
                  <title>Ohne Audio</title>
                  <guid>no-audio</guid>
                </item>
                <item>
                  <title>Folge 1</title>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
                  <itunes:duration>754</itunes:duration>
                </item>
              </channel>
            </rss>
            """,
        )

        assertEquals("Der Podcast", feed.title)
        assertEquals("Radio Eins", feed.author)
        assertEquals("Kurz", feed.description)
        assertEquals("https://example.com/cover.jpg", feed.imageUrl)
        assertEquals(2, feed.episodes.size)

        val first = feed.episodes[0]
        assertEquals("abc-2", first.guid)
        assertEquals("Folge 2", first.title)
        assertEquals("<p>Langtext</p>", first.description)
        assertEquals("https://example.com/2.mp3", first.audioUrl)
        assertEquals("audio/mpeg", first.mimeType)
        assertEquals((3600 + 120 + 3) * 1000L, first.durationMs)
        assertEquals(Instant.parse("2025-10-30T05:00:00Z").toEpochMilli(), first.publishedAt)
        assertEquals("https://example.com/2.jpg", first.imageUrl)
        assertEquals("https://example.com/2.json", first.chaptersUrl)

        val second = feed.episodes[1]
        assertEquals("guid falls back to the audio url", "https://example.com/1.mp3", second.guid)
        assertEquals(754_000L, second.durationMs)
        assertEquals(0L, second.publishedAt)
        assertNull(second.chaptersUrl)
        assertNull(second.imageUrl)
    }

    @Test
    fun `rss image falls back to the plain image element`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>T</title>
                <image><url>https://example.com/alt.jpg</url></image>
              </channel>
            </rss>
            """,
        )
        assertEquals("https://example.com/alt.jpg", feed.imageUrl)
    }

    @Test
    fun `any namespace prefix works`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:foo="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>T</title>
                <foo:author>Autor</foo:author>
                <item><enclosure url="https://example.com/a.mp3"/><foo:duration>10:00</foo:duration></item>
              </channel>
            </rss>
            """,
        )
        assertEquals("Autor", feed.author)
        assertEquals(600_000L, feed.episodes.single().durationMs)
    }

    @Test
    fun `chapters are found whichever address the podcast namespace has`() {
        // So steht es im Feed des Podcastindex („Podcasting 2.0“) selbst.
        val namespaces = listOf(
            "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md",
            "http://podcastindex.org/namespace/1.0",
            "https://podcastindex.org/namespace/1.0",
        )
        namespaces.forEach { namespace ->
            val feed = parse(
                """
                <rss version="2.0" xmlns:podcast="$namespace">
                  <channel><title>T</title>
                    <item><enclosure url="https://example.com/a.mp3"/><podcast:chapters url="https://example.com/a.json" type="application/json"/></item>
                    <item><enclosure url="https://example.com/b.mp3"/></item>
                  </channel>
                </rss>
                """,
            )
            assertEquals(namespace, "https://example.com/a.json", feed.episodes[0].chaptersUrl)
            assertNull(namespace, feed.episodes[1].chaptersUrl)
        }
    }

    @Test
    fun `atom feed with enclosure links`() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <title>Atom-Podcast</title>
              <subtitle>Untertitel</subtitle>
              <author><name>Erika</name></author>
              <logo>https://example.com/logo.png</logo>
              <entry>
                <id>tag:example.com,2025:1</id>
                <title>Eins</title>
                <summary>Zusammenfassung</summary>
                <published>2025-10-01T10:00:00+02:00</published>
                <link rel="alternate" href="https://example.com/1"/>
                <link rel="enclosure" type="audio/ogg" href="https://example.com/1.ogg" length="5"/>
                <itunes:duration>00:30</itunes:duration>
              </entry>
              <entry>
                <id>tag:example.com,2025:2</id>
                <title>Nur Text</title>
                <link rel="alternate" href="https://example.com/2"/>
              </entry>
            </feed>
            """,
        )

        assertEquals("Atom-Podcast", feed.title)
        assertEquals("Erika", feed.author)
        assertEquals("Untertitel", feed.description)
        assertEquals("https://example.com/logo.png", feed.imageUrl)
        val episode = feed.episodes.single()
        assertEquals("tag:example.com,2025:1", episode.guid)
        assertEquals("Eins", episode.title)
        assertEquals("Zusammenfassung", episode.description)
        assertEquals("https://example.com/1.ogg", episode.audioUrl)
        assertEquals("audio/ogg", episode.mimeType)
        assertEquals(30_000L, episode.durationMs)
        assertEquals(Instant.parse("2025-10-01T08:00:00Z").toEpochMilli(), episode.publishedAt)
    }

    @Test
    fun `durations`() {
        assertEquals(0L, PodcastFeedParser.parseDuration(""))
        assertEquals(0L, PodcastFeedParser.parseDuration("abc"))
        assertEquals(0L, PodcastFeedParser.parseDuration("1:2:3:4"))
        assertEquals(1_500L, PodcastFeedParser.parseDuration("1.5"))
        assertEquals(90_000L, PodcastFeedParser.parseDuration(" 1:30 "))
    }

    @Test
    fun `unreadable input is rejected`() {
        assertThrowsFeed("<html><body>Hallo</body></html>")
        assertThrowsFeed("<rss version=\"2.0\"></rss>")
        assertThrowsFeed("<rss><channel>")
        assertThrowsFeed("")
    }

    @Test
    fun `an empty feed has no episodes`() {
        val feed = parse("<rss><channel><title>Leer</title></channel></rss>")
        assertTrue(feed.episodes.isEmpty())
    }

    private fun assertThrowsFeed(xml: String) {
        try {
            parse(xml)
            throw AssertionError("Feed hätte abgelehnt werden müssen: $xml")
        } catch (_: PodcastFeedException) {
        }
    }
}
