package de.kleinabersmart.earloft.core.network.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpmlTest {

    @Test
    fun `reads feeds including nested groups and entities`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0"><head><title>x</title></head><body>
              <outline text="Kalk &amp; Welk" title="Kalk &amp; Welk" type="atom" xmlUrl="https://a.example/feed?x=1&amp;y=2" htmlUrl="https://a.example/" />
              <outline text="Gruppe">
                <outline text="Nur Text" xmlUrl="feed://b.example/rss" />
              </outline>
              <outline text="Ohne Feed" />
              <outline text="Kaputt" xmlUrl="kein feed" />
            </body></opml>
        """.trimIndent()

        val feeds = Opml.parse(xml.byteInputStream())

        assertEquals(
            listOf(
                OpmlFeed("Kalk & Welk", "https://a.example/feed?x=1&y=2", "https://a.example/"),
                OpmlFeed("Nur Text", "https://b.example/rss"),
            ),
            feeds,
        )
    }

    @Test
    fun `written opml reads back unchanged`() {
        val feeds = listOf(OpmlFeed("A & \"B\" <C>", "https://a.example/feed?x=1&y=2"), OpmlFeed("Zwei", "https://b.example/rss"))

        val text = Opml.write(feeds, "Abos", "Mon, 2 Nov 2026 14:10:40 +0100")

        assertEquals(feeds, Opml.parse(text.byteInputStream()))
    }

    @Test
    fun `rejects other xml and non xml`() {
        assertThrows(OpmlException::class.java) { Opml.parse("<rss/>".byteInputStream()) }
        assertThrows(OpmlException::class.java) { Opml.parse("kein xml".byteInputStream()) }
    }
}
