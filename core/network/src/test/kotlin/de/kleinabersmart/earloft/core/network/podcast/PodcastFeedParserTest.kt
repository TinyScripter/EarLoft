package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.model.EpisodePerson
import de.kleinabersmart.earloft.core.model.EpisodeType
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
                <title>The Podcast</title>
                <description>Short</description>
                <itunes:author>Radio Eins</itunes:author>
                <itunes:image href="https://example.com/cover.jpg"/>
                <image><url>https://example.com/alt.jpg</url></image>
                <item>
                  <title>Episode 2</title>
                  <guid isPermaLink="false">abc-2</guid>
                  <pubDate>Thu, 30 Oct 2025 06:00:00 +0100</pubDate>
                  <description>Short text</description>
                  <content:encoded><![CDATA[<p>Long text</p>]]></content:encoded>
                  <enclosure url="https://example.com/2.mp3" length="123" type="audio/mpeg"/>
                  <itunes:duration>1:02:03</itunes:duration>
                  <itunes:image href="https://example.com/2.jpg"/>
                  <pc:chapters url="https://example.com/2.json" type="application/json+chapters"/>
                </item>
                <item>
                  <title>Without audio</title>
                  <guid>no-audio</guid>
                </item>
                <item>
                  <title>Episode 1</title>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
                  <itunes:duration>754</itunes:duration>
                </item>
              </channel>
            </rss>
            """,
        )

        assertEquals("The Podcast", feed.title)
        assertEquals("Radio Eins", feed.author)
        assertEquals("Short", feed.description)
        assertEquals("https://example.com/cover.jpg", feed.imageUrl)
        assertEquals(2, feed.episodes.size)

        val first = feed.episodes[0]
        assertEquals("abc-2", first.guid)
        assertEquals("Episode 2", first.title)
        assertEquals("<p>Long text</p>", first.description)
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
    fun `doubly encoded titles, authors and chapter names are decoded once`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:psc="http://podlove.org/simple-chapters">
              <channel>
                <title>Banse &amp;amp; Buermeyer</title>
                <itunes:author><![CDATA[Philip &amp; Ulf]]></itunes:author>
                <item>
                  <title><![CDATA[BlackRock &amp; Co &#8211; &quot;Part&quot; &#x2764; &amp;lt;b&amp;gt; &unknown; & more]]></title>
                  <enclosure url="https://example.com/1.mp3" type="audio/mpeg"/>
                  <psc:chapters><psc:chapter start="0" title="Questions &amp;amp; Answers"/></psc:chapters>
                </item>
              </channel>
            </rss>
            """,
        )

        assertEquals("Banse & Buermeyer", feed.title)
        assertEquals("Philip & Ulf", feed.author)
        // Only once: whatever is still encoded afterwards was meant that way in the title.
        assertEquals("BlackRock & Co – \"Part\" ❤ &lt;b&gt; &unknown; & more", feed.episodes.single().title)
        assertEquals("Questions & Answers", feed.episodes.single().chapters.single().title)
    }

    @Test
    fun `podlove simple chapters are read in order and unreadable starts are skipped`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:psc="http://podlove.org/simple-chapters">
              <channel>
                <title>Lage</title>
                <item>
                  <title>LdN 1</title>
                  <enclosure url="https://example.com/1.m4a" type="audio/mp4"/>
                  <psc:chapters version="1.2">
                    <psc:chapter start="00:12:03.500" title=" Ukraine " image="https://example.com/ukraine.jpg"/>
                    <psc:chapter start="00:00:00.000" title="Intro" href="https://example.com"/>
                    <psc:chapter start="npt:1:02:03" title="Feedback"/>
                    <psc:chapter start="soon" title="Broken"/>
                    <psc:chapter start="75"/>
                  </psc:chapters>
                </item>
                <item>
                  <title>Without</title>
                  <enclosure url="https://example.com/2.m4a"/>
                </item>
              </channel>
            </rss>
            """,
        )

        assertEquals(
            listOf(
                ParsedChapter(0, "Intro", url = "https://example.com"),
                ParsedChapter(75_000, ""),
                ParsedChapter(723_500, "Ukraine", imageUrl = "https://example.com/ukraine.jpg"),
                ParsedChapter(3_723_000, "Feedback"),
            ),
            feed.episodes[0].chapters,
        )
        assertEquals(emptyList<ParsedChapter>(), feed.episodes[1].chapters)
    }

    @Test
    fun `own entity declarations are refused, a plain doctype is fine`() {
        val laughs = """
            <?xml version="1.0"?>
            <!DOCTYPE rss [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>
            <rss><channel><title>&lol2;</title></channel></rss>
        """
        try {
            parse(laughs)
            org.junit.Assert.fail("PodcastFeedException expected")
        } catch (_: PodcastFeedException) {
        }

        val old = parse(
            """
            <?xml version="1.0"?>
            <!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">
            <rss version="0.91"><channel><title>Old</title></channel></rss>
            """,
        )
        assertEquals("Old", old.title)
    }

    @Test
    fun `own entity declarations are refused in UTF-16 and behind a long prolog too`() {
        val laughs = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE rss [<!ENTITY lol "lol">]><rss><channel><title>&lol;</title></channel></rss>"""
        for (bytes in listOf("﻿$laughs".toByteArray(Charsets.UTF_16BE), laughs.toByteArray(Charsets.UTF_16LE))) {
            assertNull(bytes.inputStream().rejectingEntityDeclarations())
        }
        // 64 KB of comment before the declaration: it lies beyond the checked beginning.
        val padded = "<?xml version=\"1.0\"?><!--${"x".repeat(70_000)}--><!DOCTYPE rss [<!ENTITY lol \"lol\">]><rss/>"
        assertNull(padded.byteInputStream().rejectingEntityDeclarations())

        // A feed with a comment and a plain DOCTYPE, also in UTF-16, stays readable.
        val fine = """<?xml version="1.0" encoding="UTF-16"?><!-- Feed --><!DOCTYPE rss><rss version="2.0"><channel><title>Fine</title></channel></rss>"""
        assertEquals("Fine", PodcastFeedParser.parse("﻿$fine".toByteArray(Charsets.UTF_16BE).inputStream()).title)
        assertEquals("Long", parse("<rss><channel><title>Long</title><description>${"x".repeat(70_000)}</description></channel></rss>").title)
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
                <foo:author>Author</foo:author>
                <item><enclosure url="https://example.com/a.mp3"/><foo:duration>10:00</foo:duration></item>
              </channel>
            </rss>
            """,
        )
        assertEquals("Author", feed.author)
        assertEquals(600_000L, feed.episodes.single().durationMs)
    }

    @Test
    fun `chapters are found whichever address the podcast namespace has`() {
        // This is how it appears in the Podcast Index's own feed ("Podcasting 2.0").
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
              <title>Atom Podcast</title>
              <subtitle>Subtitle</subtitle>
              <author><name>Erika</name></author>
              <logo>https://example.com/logo.png</logo>
              <entry>
                <id>tag:example.com,2025:1</id>
                <title>One</title>
                <summary>Summary</summary>
                <published>2025-10-01T10:00:00+02:00</published>
                <link rel="alternate" href="https://example.com/1"/>
                <link rel="enclosure" type="audio/ogg" href="https://example.com/1.ogg" length="5"/>
                <itunes:duration>00:30</itunes:duration>
              </entry>
              <entry>
                <id>tag:example.com,2025:2</id>
                <title>Text only</title>
                <link rel="alternate" href="https://example.com/2"/>
              </entry>
            </feed>
            """,
        )

        assertEquals("Atom Podcast", feed.title)
        assertEquals("Erika", feed.author)
        assertEquals("Subtitle", feed.description)
        assertEquals("https://example.com/logo.png", feed.imageUrl)
        val episode = feed.episodes.single()
        assertEquals("tag:example.com,2025:1", episode.guid)
        assertEquals("One", episode.title)
        assertEquals("Summary", episode.description)
        assertEquals("https://example.com/1.ogg", episode.audioUrl)
        assertEquals("audio/ogg", episode.mimeType)
        assertEquals(30_000L, episode.durationMs)
        assertEquals(Instant.parse("2025-10-01T08:00:00Z").toEpochMilli(), episode.publishedAt)
    }

    @Test
    fun `rss web pages come from link, otherwise from a permalink guid`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
              <channel>
                <title>P</title>
                <atom:link href="https://example.com/feed.xml" rel="self" type="application/rss+xml"/>
                <link>https://example.com/</link>
                <item>
                  <title>With link</title>
                  <link> https://example.com/1 </link>
                  <guid isPermaLink="true">https://example.com/guid-1</guid>
                  <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>Only guid</title>
                  <guid>https://example.com/2</guid>
                  <enclosure url="https://cdn.example.com/2.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>guid without permalink</title>
                  <guid isPermaLink="false">https://example.com/3</guid>
                  <enclosure url="https://cdn.example.com/3.mp3" type="audio/mpeg"/>
                </item>
                <item>
                  <title>guid without address</title>
                  <guid>abc-4</guid>
                  <enclosure url="https://cdn.example.com/4.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
            """,
        )
        assertEquals("https://example.com/", feed.websiteUrl)
        assertEquals(listOf("https://example.com/1", "https://example.com/2", null, null), feed.episodes.map { it.webUrl })
    }

    @Test
    fun `atom web pages come from the alternate link`() {
        val feed = parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>P</title>
              <link rel="self" href="https://example.com/atom.xml"/>
              <link href="https://example.com/"/>
              <entry>
                <id>e1</id>
                <title>Episode</title>
                <link rel="enclosure" href="https://cdn.example.com/1.mp3" type="audio/mpeg"/>
                <link rel="alternate" type="text/html" href="https://example.com/1"/>
              </entry>
            </feed>
            """,
        )
        assertEquals("https://example.com/", feed.websiteUrl)
        assertEquals("https://example.com/1", feed.episodes.single().webUrl)
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
        assertThrowsFeed("<html><body>Hello</body></html>")
        assertThrowsFeed("<rss version=\"2.0\"></rss>")
        assertThrowsFeed("<rss><channel>")
        assertThrowsFeed("")
    }

    @Test
    fun `podcasting 2 0 and itunes episode details`() {
        val feed = parse(
            """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>P</title>
                <podcast:guid> 917393E3-1B1E-5CEF-ACE4-EDAA54E1F810 </podcast:guid>
                <podcast:funding url="mailto:x@example.com">Mail</podcast:funding>
                <podcast:funding url="https://example.com/support">Support us &amp;amp; the podcast</podcast:funding>
                <podcast:person role="host" img="https://example.com/anna.jpg">Anna</podcast:person>
                <item>
                  <title>Season 2, Episode 12</title>
                  <enclosure url="https://example.com/12.mp3"/>
                  <itunes:season>2</itunes:season>
                  <itunes:episode>12</itunes:episode>
                  <itunes:episodeType>Bonus</itunes:episodeType>
                  <podcast:person role="Guest" href="https://example.com/bob">Bob</podcast:person>
                  <podcast:person>  </podcast:person>
                  <podcast:transcript url="https://example.com/12.html" type="text/html"/>
                  <podcast:transcript url="https://example.com/12.pdf" type="application/pdf"/>
                  <podcast:transcript url="https://example.com/12.vtt" type="text/vtt"/>
                </item>
                <item>
                  <title>Trailer</title>
                  <enclosure url="https://example.com/t.mp3"/>
                  <itunes:episodeType>trailer</itunes:episodeType>
                  <podcast:season>1</podcast:season>
                  <podcast:episode>1.5</podcast:episode>
                  <podcast:transcript url="https://example.com/t.srt"/>
                </item>
              </channel>
            </rss>
            """,
        )
        assertEquals("917393e3-1b1e-5cef-ace4-edaa54e1f810", feed.podcastGuid)
        assertEquals("https://example.com/support", feed.fundingUrl)
        assertEquals("Support us & the podcast", feed.fundingLabel)

        val bonus = feed.episodes[0]
        assertEquals(2, bonus.season)
        assertEquals(12, bonus.episodeNumber)
        assertEquals(EpisodeType.BONUS, bonus.episodeType)
        // If the episode names its own contributors, the podcast's don't apply; one without a name is dropped.
        assertEquals(listOf(EpisodePerson("Bob", role = "guest", url = "https://example.com/bob")), bonus.persons)
        assertEquals("https://example.com/12.vtt", bonus.transcriptUrl)
        assertEquals("text/vtt", bonus.transcriptType)

        val trailer = feed.episodes[1]
        assertEquals(1, trailer.season)
        assertNull(trailer.episodeNumber)
        assertEquals(EpisodeType.TRAILER, trailer.episodeType)
        assertEquals(listOf(EpisodePerson("Anna", role = "host", imageUrl = "https://example.com/anna.jpg")), trailer.persons)
        // Without a type, the file extension counts.
        assertEquals("https://example.com/t.srt", trailer.transcriptUrl)
    }

    @Test
    fun `without podcasting 2 0 nothing is set`() {
        val feed = parse("<rss><channel><title>P</title><item><enclosure url=\"https://e.com/1.mp3\"/></item></channel></rss>")
        assertNull(feed.podcastGuid)
        assertNull(feed.fundingUrl)
        val episode = feed.episodes.single()
        assertNull(episode.season)
        assertNull(episode.episodeType)
        assertNull(episode.transcriptUrl)
        assertTrue(episode.persons.isEmpty())
    }

    @Test
    fun `an empty feed has no episodes`() {
        val feed = parse("<rss><channel><title>Empty</title></channel></rss>")
        assertTrue(feed.episodes.isEmpty())
    }

    @Test
    fun `dates with zone names, wrong weekdays and two-digit years are understood`() {
        val expected = Instant.parse("2025-10-30T11:00:00Z").toEpochMilli()
        listOf(
            "Thu, 30 Oct 2025 06:00:00 EST",
            "Thu, 30 Oct 2025 11:00:00 UTC",
            "Thu, 30 Oct 2025 11:00:00 UT",
            "Thu, 30 Oct 2025 11:00:00 Z",
            "Mon, 30 Oct 2025 11:00:00 GMT",
            "Thursday, 30 Oct 2025 11:00:00 +0000",
            "Thu, 30 Oct 25 12:00:00 +01:00",
            "30 Oct 2025 11:00 GMT",
            "Do, 30 Okt 2025 12:00:00 MEZ",
            "2025-10-30T11:00:00Z",
        ).forEach { raw -> assertEquals(raw, expected, PodcastFeedParser.parseRfc1123(raw)) }
        assertEquals(0L, PodcastFeedParser.parseRfc1123("yesterday"))
    }

    @Test
    fun `html entities outside cdata do not make the feed invalid`() {
        val feed = parse(
            """<rss><channel><title>Caf&eacute;&nbsp;Talk</title><item><title>Folge&nbsp;1 &ndash; Start</title>
            <description>A &amp; B</description><enclosure url="https://e.com/1.mp3"/></item></channel></rss>""",
        )
        assertEquals("Café Talk".replace(' ', ' '), feed.title.replace(' ', ' '))
        assertEquals("Folge 1 – Start", feed.episodes.single().title.replace(' ', ' '))
        assertEquals("A & B", feed.episodes.single().description)
    }

    @Test
    fun `the http charset applies only to documents without their own encoding`() {
        val latin1 = "<rss><channel><title>Bücher</title></channel></rss>".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Bücher", PodcastFeedParser.parse(latin1.inputStream(), charset = "ISO-8859-1").title)

        val declared = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><title>Bücher</title></channel></rss>"
            .toByteArray(Charsets.UTF_8)
        assertEquals("Bücher", PodcastFeedParser.parse(declared.inputStream(), charset = "ISO-8859-1").title)
    }

    private fun assertThrowsFeed(xml: String) {
        try {
            parse(xml)
            throw AssertionError("Feed should have been rejected: $xml")
        } catch (_: PodcastFeedException) {
        }
    }
}
