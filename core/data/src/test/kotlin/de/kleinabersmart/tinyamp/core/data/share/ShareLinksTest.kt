package de.kleinabersmart.tinyamp.core.data.share

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
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
    fun `an artist search names only the artist`() {
        val url = ShareLinks.musicBrainz(MusicBrainzKind.ARTIST, null, "Die Ärzte").toHttpUrl()
        assertEquals("artist:\"Die Ärzte\"", url.queryParameter("query"))
        assertEquals("artist", url.queryParameter("type"))
    }
}
