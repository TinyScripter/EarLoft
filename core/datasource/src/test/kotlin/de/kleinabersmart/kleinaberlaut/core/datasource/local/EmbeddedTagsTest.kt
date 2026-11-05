package de.kleinabersmart.kleinaberlaut.core.datasource.local

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedTagsTest {

    private val recording = "11111111-2222-3333-4444-555555555555"
    private val release = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val artist = "01234567-89ab-cdef-0123-456789abcdef"

    @Test
    fun `vorbis comments`() {
        val tags = EmbeddedTags.from(
            listOf(
                Metadata(
                    VorbisComment("musicbrainz_trackid", recording),
                    VorbisComment("MUSICBRAINZ_ALBUMID", release.uppercase()),
                    VorbisComment("MUSICBRAINZ_ARTISTID", artist),
                    VorbisComment("LYRICS", "[00:01.00]Hallo"),
                ),
            ),
        )

        assertEquals(recording, tags.recordingMbid)
        assertEquals(release, tags.releaseMbid)
        assertEquals(artist, tags.artistMbid)
        assertEquals("[00:01.00]Hallo", tags.lyrics)
    }

    @Test
    fun `id3 txxx and ufid`() {
        val ufid = "http://musicbrainz.org".toByteArray(Charsets.ISO_8859_1) + 0 + recording.toByteArray()
        val tags = EmbeddedTags.from(
            listOf(
                Metadata(
                    TextInformationFrame("TXXX", "MusicBrainz Album Id", listOf(release)),
                    BinaryFrame("UFID", ufid),
                    BinaryFrame("UFID", "other".toByteArray() + 0 + "x".toByteArray()),
                ),
            ),
        )

        assertEquals(recording, tags.recordingMbid)
        assertEquals(release, tags.releaseMbid)
    }

    @Test
    fun `mp4 freeform atoms and lyrics`() {
        val tags = EmbeddedTags.from(
            listOf(
                Metadata(
                    InternalFrame("com.apple.iTunes", "MusicBrainz Track Id", recording),
                    TextInformationFrame("USLT", null, listOf("Text")),
                ),
            ),
        )

        assertEquals(recording, tags.recordingMbid)
        assertEquals("Text", tags.lyrics)
    }

    @Test
    fun `several artist ids are ambiguous`() {
        val tags = EmbeddedTags.from(listOf(Metadata(VorbisComment("MUSICBRAINZ_ARTISTID", "$artist;$recording"))))

        assertNull(tags.artistMbid)
    }

    @Test
    fun `uslt decodes encodings`() {
        val latin = byteArrayOf(0) + "deu".toByteArray() + 0 + "Grüße".toByteArray(Charsets.ISO_8859_1)
        val utf8 = byteArrayOf(3) + "deu".toByteArray() + "beschr".toByteArray() + 0 + "Grüße".toByteArray()
        val utf16 = byteArrayOf(2) + "deu".toByteArray() + byteArrayOf(0, 0) + "Grüße".toByteArray(Charsets.UTF_16BE)

        assertEquals("Grüße", EmbeddedTags.decodeUslt(latin))
        assertEquals("Grüße", EmbeddedTags.decodeUslt(utf8))
        assertEquals("Grüße", EmbeddedTags.decodeUslt(utf16))
    }

    @Test
    fun `nothing found`() {
        assertEquals(EmbeddedTags.EMPTY, EmbeddedTags.from(emptyList()))
    }
}
