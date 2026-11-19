package de.kleinabersmart.earloft.core.datasource.local

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPodcastLayoutTest {

    @Test
    fun `a subfolder is a podcast, loose files go by album then artist then the shared name`() {
        val podcasts = LocalPodcastLayout.group(
            listOf(
                row(1, "/storage/emulated/0/Podcasts/My podcast/a.mp3"),
                row(2, "/storage/emulated/0/Podcasts/swr.mp3", album = "SWR"),
                row(3, "/storage/emulated/0/Podcasts/x.mp3", album = "Podcasts", artist = "<unknown>"),
                row(4, "/storage/emulated/0/Podcasts/y.mp3", album = "Podcasts", artist = "Station"),
                row(5, "/storage/emulated/0/Podcasts/z.mp3", album = "Podcasts", artist = "<unknown>"),
                row(6, "/storage/emulated/0/Music/song.mp3", album = "Songs"),
            ),
            untitled = "This device",
        )

        assertEquals(listOf("My podcast", "Station", "SWR", "This device"), podcasts.map { it.name })
        assertEquals(listOf(3L, 5L), podcasts.first { it.name == "This device" }.files.map { it.id })
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
        assertNull(LocalPodcastLayout.parseTagDate("yesterday"))
        assertNull(LocalPodcastLayout.parseTagDate(null))
    }

    @Test
    fun `episode tags give date, description and ordered chapters without jingle marks`() {
        fun chapter(id: String, start: Int, end: Int, title: String?) = ChapterFrame(
            id, start, end, 0, 0,
            if (title == null) emptyArray<Id3Frame>() else arrayOf<Id3Frame>(TextInformationFrame("TIT2", null, listOf(title))),
        )

        val tags = SpokenTags.from(
            listOf(
                Metadata(
                    TextInformationFrame("TDRC", null, listOf("2026-10-29")),
                    TextInformationFrame("TXXX", "description", listOf("An episode")),
                    chapter("c2", 1_843_835, 3_493_981, "Interview"),
                    chapter("c1", 467_210, 1_255_165, "Intro"),
                    chapter("c3", 1_255_165, 1_255_335, "Cut mark"),
                    chapter("c4", 4_000_000, 4_100_000, null),
                ),
            ),
        )

        assertEquals("2026-10-29", tags.date)
        assertEquals("An episode", tags.description)
        assertEquals(listOf("Intro", "Interview", "c4"), tags.chapters.map { it.title })
        assertEquals(467_210L, tags.chapters.first().startMs)
    }

    @Test
    fun `production marks are no chapters, nested or mostly without a name`() {
        fun chapter(id: String, start: Int, end: Int, title: String) =
            ChapterFrame(id, start, end, 0, 0, arrayOf<Id3Frame>(TextInformationFrame("TIT2", null, listOf(title))))
        // As in ARD's files (on the emulator): one long chapter, then nested marks.
        val nested = SpokenTags.from(
            listOf(
                Metadata(
                    chapter("a", 0, 2_123_676, "General Podcast V2"),
                    chapter("b", 2_123_676, 2_184_786, "Credits"),
                    chapter("c", 2_123_676, 2_134_140, "Part"),
                ),
            ),
        )
        val unnamed = SpokenTags.from(
            listOf(
                Metadata(
                    chapter("a", 212_214, 615_516, "Refrain 3"),
                    chapter("b", 615_516, 872_830, "ch1"),
                    chapter("c", 872_830, 1_137_639, "ch2"),
                ),
            ),
        )

        assertEquals(emptyList<LocalBookChapter>(), nested.chapters)
        assertEquals(emptyList<LocalBookChapter>(), unnamed.chapters)
    }

    @Test
    fun `numbered chapters from the start of the file are chapters`() {
        fun chapter(id: String, start: Int, end: Int, title: String) =
            ChapterFrame(id, start, end, 0, 0, arrayOf<Id3Frame>(TextInformationFrame("TIT2", null, listOf(title))))
        fun tags(vararg titles: String) = SpokenTags.from(
            listOf(Metadata(*titles.mapIndexed { i, title -> chapter("c$i", i * 600_000, (i + 1) * 600_000, title) }.toTypedArray())),
        )

        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), tags("Chapter 1", "Chapter 2", "Chapter 3").chapters.map { it.title })
        assertEquals(3, tags("Kapitel 1", "Kapitel 2", "Kapitel 3").chapters.size)
        assertEquals(3, tags("001", "002", "003").chapters.size)
        assertEquals(4, tags("Foreword", "Chapter 1", "Chapter 2", "Chapter 3").chapters.size)
        // Numbers with gaps are IDs, not chapters.
        assertEquals(emptyList<LocalBookChapter>(), tags("ch1", "ch4", "ch9").chapters)
    }

    @Test
    fun `ID3v2_3 year and day-month are joined`() {
        val both = SpokenTags.from(listOf(Metadata(TextInformationFrame("TYER", null, listOf("2026")), TextInformationFrame("TDAT", null, listOf("2110")))))
        val yearOnly = SpokenTags.from(listOf(Metadata(TextInformationFrame("TYER", null, listOf("2026")))))

        assertEquals("2026-10-21", both.date)
        assertEquals("2026", yearOnly.date)
        assertEquals(2026, both.year)
    }

    @Test
    fun `subtitle and year come from ID3, Vorbis or the free MP4 fields`() {
        val id3 = SpokenTags.from(listOf(Metadata(TextInformationFrame("TIT3", null, listOf(" A novel ")), TextInformationFrame("TDRC", null, listOf("2019-05-01")))))
        val vorbis = SpokenTags.from(listOf(Metadata(VorbisComment("subtitle", "Part 2"), VorbisComment("DATE", "2021"))))
        val mp4 = SpokenTags.from(listOf(Metadata(InternalFrame("com.apple.iTunes", "SUBTITLE", "Crime novel"))))
        val none = SpokenTags.from(listOf(Metadata(TextInformationFrame("TDRC", null, listOf("20190")))))

        assertEquals("A novel", id3.subtitle)
        assertEquals(2019, id3.year)
        assertEquals("Part 2", vorbis.subtitle)
        assertEquals(2021, vorbis.year)
        assertEquals("Crime novel", mp4.subtitle)
        assertNull(mp4.year)
        assertNull(none.year)
    }

    private fun row(id: Long, path: String, album: String? = null, artist: String? = null) =
        AudioFileRow(id, path, null, artist, null, album, 1000, 0, "audio/mpeg", 1, 0, 0)
}
