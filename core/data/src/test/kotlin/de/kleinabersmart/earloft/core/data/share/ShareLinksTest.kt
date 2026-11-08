package de.kleinabersmart.earloft.core.data.share

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinksTest {

    @Test
    fun `a known mbid links to its page`() {
        assertEquals(
            "https://musicbrainz.org/release/0a1b2c3d-4e5f-6789-abcd-ef0123456789",
            ShareLinks.musicBrainz(MusicBrainzKind.RELEASE, " 0A1B2C3D-4E5F-6789-ABCD-EF0123456789 ", "Album", "Artist"),
        )
        assertEquals(
            "https://musicbrainz.org/artist/0a1b2c3d-4e5f-6789-abcd-ef0123456789",
            ShareLinks.musicBrainz(MusicBrainzKind.ARTIST, "0a1b2c3d-4e5f-6789-abcd-ef0123456789", "Artist"),
        )
    }

    @Test
    fun `without a usable mbid it searches by title and artist`() {
        val url = ShareLinks.musicBrainz(MusicBrainzKind.RECORDING, "not-an-id", "Say \"Hi\"", "AC/DC").toHttpUrl()
        assertEquals("musicbrainz.org", url.host)
        assertEquals("/search", url.encodedPath)
        assertEquals("recording:\"Say \\\"Hi\\\"\" AND artist:\"AC/DC\"", url.queryParameter("query"))
        assertEquals("recording", url.queryParameter("type"))
        assertEquals("advanced", url.queryParameter("method"))
    }

    @Test
    fun `a book with an isbn links to open library`() {
        assertEquals("https://openlibrary.org/isbn/9783844917056", ShareLinks.openLibrary("978-3-8449-1705-6", "T", "A"))
        assertEquals("https://openlibrary.org/isbn/380252545X", ShareLinks.openLibrary(" 3-8025-2545-x ", "T", "A"))
    }

    @Test
    fun `a book without a usable isbn searches by title and author`() {
        val url = ShareLinks.openLibrary("B00ASIN123", "Die Räuber", "Friedrich Schiller").toHttpUrl()
        assertEquals("openlibrary.org", url.host)
        assertEquals("/search", url.encodedPath)
        assertEquals("Die Räuber", url.queryParameter("title"))
        assertEquals("Friedrich Schiller", url.queryParameter("author"))
        assertEquals(null, ShareLinks.openLibrary(null, "T", " ").toHttpUrl().queryParameter("author"))
    }

    @Test
    fun `the book search leaves out subtitles, notes and translators`() {
        assertEquals("Kill Shot", ShareLinks.mainTitle("Kill Shot (Ein Harry-Hole-Krimi 14)"))
        assertEquals("Milchschaumschläger", ShareLinks.mainTitle("Milchschaumschläger: Ein Café-Hörbuch"))
        assertEquals("Carl’s Doomsday Scenario", ShareLinks.mainTitle("Carl’s Doomsday Scenario (Unabridged) [Book 2]"))
        assertEquals("(Nur Klammer)", ShareLinks.mainTitle("(Nur Klammer)"))
        assertEquals("Jo Nesbø", ShareLinks.mainAuthor("Günther Frauenlob — Translator, Jo Nesbø"))
        assertEquals("Matt Dinniman", ShareLinks.mainAuthor("Matt Dinniman, Ruggero Leò - translator"))
        assertEquals(null, ShareLinks.mainAuthor("Anna Übersetzerin (Übersetzerin)"))
    }

    @Test
    fun `platform pages are never shared`() {
        listOf(
            "https://podcasts.apple.com/de/podcast/id123",
            "https://open.spotify.com/show/abc",
            "https://www.amazon.co.uk/dp/B000",
            "https://www.audible.de/pd/B000",
            "https://music.amazon.de/podcasts/x",
            "https://www.youtube.com/watch?v=x",
            "https://podcasters.spotify.com/pod/show/x",
            "https://anchor.fm/x",
        ).forEach { assertEquals(it, null, ShareLinks.publicPage(it)) }
    }

    @Test
    fun `own pages and hosters stay`() {
        listOf(
            "https://www.ardsounds.de/sendung/x/urn:ard:show:1/",
            "https://example.podigee.io/1-folge",
            "https://spotifyfans.example.org/",
            "http://example.com/folge",
        ).forEach { assertEquals(it, ShareLinks.publicPage(it)) }
        assertEquals(null, ShareLinks.publicPage("local:abc"))
        assertEquals(null, ShareLinks.publicPage("  "))
    }

    @Test
    fun `feeds and audio files are shared wherever they are`() {
        assertEquals("https://anchor.fm/s/1/podcast/rss", ShareLinks.sourceUrl(" https://anchor.fm/s/1/podcast/rss "))
        assertEquals(null, ShareLinks.sourceUrl("ardsounds:urn:ard:show:1"))
    }

    @Test
    fun `an artist search names only the artist`() {
        val url = ShareLinks.musicBrainz(MusicBrainzKind.ARTIST, null, "Die Ärzte").toHttpUrl()
        assertEquals("artist:\"Die Ärzte\"", url.queryParameter("query"))
        assertEquals("artist", url.queryParameter("type"))
    }

    @Test
    fun `feeds with credentials, at home or on an own server are private`() {
        assertTrue(ShareLinks.isPrivateSource("https://www.patreon.com/rss/show?auth=abc123"))
        assertTrue(ShareLinks.isPrivateSource("https://feeds.example.com/p.xml?access_token=x"))
        assertTrue(ShareLinks.isPrivateSource("https://user:pw@example.com/feed.xml"))
        assertTrue(ShareLinks.isPrivateSource("http://192.168.0.140/feed.xml"))
        assertTrue(ShareLinks.isPrivateSource("http://nas.local/feed.xml"))
        assertTrue(ShareLinks.isPrivateSource("http://nas:13378/feed"))
        assertTrue(ShareLinks.isPrivateSource("https://abs.example.org/feed/1", ownHosts = setOf("abs.example.org")))

        assertFalse(ShareLinks.isPrivateSource("https://www.tagesschau.de/export/podcast/hi/tagesschau-in-100-sekunden/"))
        assertFalse(ShareLinks.isPrivateSource("https://feeds.example.com/podcast.xml?format=mp3"))
        assertFalse(ShareLinks.isPrivateSource("http://172.32.0.1/feed.xml"))
    }
}
