package de.kleinabersmart.kleinaberlaut.core.datasource.local

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPodcastLayoutTest {

    @Test
    fun `a subfolder is a podcast, loose files go by album then artist then the shared name`() {
        val podcasts = LocalPodcastLayout.group(
            listOf(
                row(1, "/storage/emulated/0/Podcasts/Mein Podcast/a.mp3"),
                row(2, "/storage/emulated/0/Podcasts/swr.mp3", album = "SWR"),
                row(3, "/storage/emulated/0/Podcasts/x.mp3", album = "Podcasts", artist = "<unknown>"),
                row(4, "/storage/emulated/0/Podcasts/y.mp3", album = "Podcasts", artist = "Sender"),
                row(5, "/storage/emulated/0/Podcasts/z.mp3", album = "Podcasts", artist = "<unknown>"),
                row(6, "/storage/emulated/0/Music/lied.mp3", album = "Lieder"),
            ),
            untitled = "Dieses Gerät",
        )

        assertEquals(listOf("Dieses Gerät", "Mein Podcast", "Sender", "SWR"), podcasts.map { it.name })
        assertEquals(listOf(3L, 5L), podcasts.first { it.name == "Dieses Gerät" }.files.map { it.id })
    }

    @Test
    fun `episode key is the path from the Podcasts folder`() {
        assertEquals("Podcasts/A/b.mp3", LocalPodcastLayout.key(row(1, "/storage/1234-ABCD/Podcasts/A/b.mp3")))
    }

    @Test
    fun `reads dates in the usual tag shapes`() {
        val day = 24 * 60 * 60 * 1000L
        val oct29 = LocalPodcastLayout.parseTagDate("2026-10-29")!!
        assertEquals(oct29, LocalPodcastLayout.parseTagDate("20261029"))
        assertEquals(oct29, LocalPodcastLayout.parseTagDate("2026-10-29T08:15:00"))
        assertEquals(oct29 + day, LocalPodcastLayout.parseTagDate("2026-10-30"))
        assertEquals(LocalPodcastLayout.parseTagDate("2026-01-01"), LocalPodcastLayout.parseTagDate("2026"))
        assertNull(LocalPodcastLayout.parseTagDate("2026-13-40"))
        assertNull(LocalPodcastLayout.parseTagDate("gestern"))
        assertNull(LocalPodcastLayout.parseTagDate(null))
    }

    @Test
    fun `episode tags give date, description and ordered chapters without jingle marks`() {
        fun chapter(id: String, start: Int, end: Int, title: String?) = ChapterFrame(
            id, start, end, 0, 0,
            if (title == null) emptyArray<Id3Frame>() else arrayOf<Id3Frame>(TextInformationFrame("TIT2", null, listOf(title))),
        )

        val tags = EpisodeTags.from(
            listOf(
                Metadata(
                    TextInformationFrame("TDRC", null, listOf("2026-10-29")),
                    TextInformationFrame("TXXX", "description", listOf("Eine Folge")),
                    chapter("c2", 1_843_835, 3_493_981, "Refrain 2"),
                    chapter("c1", 467_210, 1_255_165, "ch1"),
                    chapter("c3", 1_255_165, 1_255_335, "Schnittmarke"),
                    chapter("c4", 4_000_000, 4_100_000, null),
                ),
            ),
        )

        assertEquals("2026-10-29", tags.date)
        assertEquals("Eine Folge", tags.description)
        assertEquals(listOf("ch1", "Refrain 2", "c4"), tags.chapters.map { it.title })
        assertEquals(467_210L, tags.chapters.first().startMs)
    }

    @Test
    fun `ID3v2_3 year and day-month are joined`() {
        val both = EpisodeTags.from(listOf(Metadata(TextInformationFrame("TYER", null, listOf("2026")), TextInformationFrame("TDAT", null, listOf("2110")))))
        val yearOnly = EpisodeTags.from(listOf(Metadata(TextInformationFrame("TYER", null, listOf("2026")))))

        assertEquals("2026-10-21", both.date)
        assertEquals("2026", yearOnly.date)
    }

    private fun row(id: Long, path: String, album: String? = null, artist: String? = null) =
        AudioFileRow(id, path, null, artist, null, album, 1000, 0, "audio/mpeg", 1, 0, 0)
}
