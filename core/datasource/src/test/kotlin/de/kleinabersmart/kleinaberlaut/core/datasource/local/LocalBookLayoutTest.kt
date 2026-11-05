package de.kleinabersmart.kleinaberlaut.core.datasource.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalBookLayoutTest {

    @Test
    fun `a folder is one book and a file in the root is a book of its own`() {
        val books = LocalBookLayout.group(
            listOf(
                row(1, "/storage/emulated/0/Audiobooks/Wurst.m4b"),
                row(2, "/storage/emulated/0/Audiobooks/Ringe/b.mp3"),
                row(3, "/storage/emulated/0/Audiobooks/Ringe/a.mp3"),
                row(4, "/storage/emulated/0/Music/lied.mp3"),
            ),
        )

        assertEquals(listOf("Audiobooks/Ringe/", "Audiobooks/Wurst.m4b"), books.map { it.key })
        assertEquals(listOf(3L, 2L), books[0].files.map { it.id })
        assertEquals("Ringe", books[0].folderName)
        assertNull(books[1].folderName)
    }

    @Test
    fun `finds the folder on a memory card too`() {
        assertEquals("Audiobooks/X/a.mp3", LocalBookLayout.relativePath("/storage/1234-ABCD/Audiobooks/X/a.mp3"))
        assertNull(LocalBookLayout.relativePath("/storage/emulated/0/Audiobooks"))
    }

    @Test
    fun `track numbers decide when all files have one`() {
        val sorted = LocalBookLayout.sortInReadingOrder(
            listOf(row(1, "/Audiobooks/B/a.mp3", track = 2), row(2, "/Audiobooks/B/b.mp3", track = 1)),
        )

        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `the count in the title beats meaningless file names`() {
        val sorted = LocalBookLayout.sortInReadingOrder(
            listOf(
                row(1, "/Audiobooks/B/12209255.mp3", title = "Ringe (04/30): Verfolger | J.R.R. Tolkien (25.12.2022)"),
                row(2, "/Audiobooks/B/12209259.mp3", title = "Ringe (03/30): Aufbruch | J.R.R. Tolkien (25.12.2022)"),
                row(3, "/Audiobooks/B/12209777.mp3", title = "Ringe (10/30): Rat | J.R.R. Tolkien (25.12.2022)"),
            ),
        )

        assertEquals(listOf(2L, 1L, 3L), sorted.map { it.id })
    }

    @Test
    fun `file names are compared with numbers as numbers`() {
        val sorted = LocalBookLayout.sortInReadingOrder(
            listOf(row(1, "/Audiobooks/B/Kapitel 10.mp3"), row(2, "/Audiobooks/B/Kapitel 2.mp3")),
        )

        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun `a date is not a count`() {
        assertEquals(4, LocalBookLayout.titleOrdinal("Folge (04/30) vom 25.12.2022"))
        assertNull(LocalBookLayout.titleOrdinal("Sendung vom 25/12/2022"))
        assertEquals(3, LocalBookLayout.titleOrdinal("Teil 3 von 12"))
    }

    @Test
    fun `chapter title drops what follows the bar and falls back to the file name`() {
        assertEquals("Der Herr der Ringe (04/30): Verfolger", LocalBookLayout.chapterTitle("Der Herr der Ringe (04/30): Verfolger | Tolkien", "x.mp3"))
        assertEquals("Kapitel 1", LocalBookLayout.chapterTitle(null, "Kapitel_1.mp3"))
    }

    @Test
    fun `single file takes its own title, a folder the shared album`() {
        val single = LocalBookFiles("Audiobooks/W.m4b", null, listOf(row(1, "/Audiobooks/W.m4b", title = "Wurst", album = "Wurst: Comedy", artist = "Kalpenstein")))
        val folder = LocalBookFiles(
            "Audiobooks/Ringe/",
            "Ringe",
            listOf(row(1, "/Audiobooks/Ringe/a.mp3", title = "1", album = "Tolkien: Ringe"), row(2, "/Audiobooks/Ringe/b.mp3", title = "2", album = "Tolkien: Ringe")),
        )
        val untagged = LocalBookFiles("Audiobooks/Leer/", "Leer", listOf(row(1, "/Audiobooks/Leer/a.mp3", album = "<unknown>", artist = "<unknown>")))

        assertEquals("Wurst", LocalBookLayout.bookTitle(single))
        assertEquals("Kalpenstein", LocalBookLayout.bookAuthor(single))
        assertEquals("Tolkien: Ringe", LocalBookLayout.bookTitle(folder))
        assertEquals("", LocalBookLayout.bookAuthor(untagged))
    }

    @Test
    fun `fingerprint changes with the files`() {
        val a = LocalBookFiles("k", null, listOf(row(1, "/Audiobooks/a.mp3", modified = 10)))
        val b = LocalBookFiles("k", null, listOf(row(1, "/Audiobooks/a.mp3", modified = 11)))

        assertEquals(LocalBookLayout.fingerprint(a), LocalBookLayout.fingerprint(a))
        assert(LocalBookLayout.fingerprint(a) != LocalBookLayout.fingerprint(b))
    }

    private fun row(
        id: Long,
        path: String,
        title: String? = null,
        album: String? = null,
        artist: String? = null,
        track: Int = 0,
        modified: Long = 0,
    ) = AudioFileRow(id, path, title, artist, null, album, 1000, track, "audio/mpeg", 1, 0, modified)
}
