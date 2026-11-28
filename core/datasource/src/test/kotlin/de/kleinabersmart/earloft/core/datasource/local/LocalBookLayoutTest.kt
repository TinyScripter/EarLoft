package de.kleinabersmart.earloft.core.datasource.local

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
                row(4, "/storage/emulated/0/Music/song.mp3"),
            ),
        )

        assertEquals(listOf("Audiobooks/Ringe/", "Audiobooks/Wurst.m4b"), books.map { it.key })
        assertEquals(listOf(3L, 2L), books[0].files.map { it.id })
        assertEquals("Ringe", books[0].folderName)
        assertNull(books[1].folderName)
    }

    @Test
    fun `the same book on two volumes is taken from one of them`() {
        // review5 P36: while moving to the SD card, the chapters came twice and the m4b swapped places at every scan.
        val books = LocalBookLayout.group(
            listOf(
                row(1, "/storage/emulated/0/Audiobooks/Ringe/a.mp3").copy(volume = "external_primary"),
                row(2, "/storage/emulated/0/Audiobooks/Ringe/b.mp3").copy(volume = "external_primary"),
                row(3, "/storage/1234-ABCD/Audiobooks/Ringe/a.mp3").copy(volume = "1234-abcd"),
                row(4, "/storage/emulated/0/Audiobooks/Wurst.m4b").copy(volume = "external_primary"),
                row(5, "/storage/1234-ABCD/Audiobooks/Wurst.m4b").copy(volume = "1234-abcd"),
            ),
        )

        assertEquals(listOf("Audiobooks/Ringe/", "Audiobooks/Wurst.m4b"), books.map { it.key })
        assertEquals(listOf(1L, 2L), books[0].files.map { it.id })
        assertEquals(listOf(4L), books[1].files.map { it.id })
    }

    @Test
    fun `disc folders belong to the book above them, disc by disc`() {
        // review5 P35: "CD 1" and "CD 2" became two books, and "Continue listening" ended after the first disc.
        val base = "/storage/emulated/0/Audiobooks/Autor/Buch"
        val books = LocalBookLayout.group(
            listOf(
                row(1, "$base/CD 2/01.mp3", track = 1),
                row(2, "$base/CD 10/01.mp3", track = 1),
                row(3, "$base/CD 1/02.mp3", track = 2),
                row(4, "$base/CD 1/01.mp3", track = 1),
                row(5, "$base/CD 2/02.mp3", track = 2),
            ),
        )

        assertEquals(listOf("Audiobooks/Autor/Buch/"), books.map { it.key })
        assertEquals(listOf(4L, 3L, 1L, 5L, 2L), books.single().files.map { it.id })
        assertEquals("Buch", books.single().folderName)
        assertEquals(base, books.single().folderPath)
    }

    @Test
    fun `parts of a series whose tags name different books stay books of their own`() {
        val base = "/storage/emulated/0/Audiobooks/Autor/Serie"
        val books = LocalBookLayout.group(
            listOf(
                row(1, "$base/Teil 1/01.mp3", album = "Die Ernte", track = 1),
                row(2, "$base/Teil 1/02.mp3", album = "Die Ernte", track = 2),
                row(3, "$base/Teil 2/01.mp3", album = "Der Winter", track = 1),
                row(4, "$base/intro.mp3", album = "Serie"),
            ),
        )

        assertEquals(
            listOf("Audiobooks/Autor/Serie/", "Audiobooks/Autor/Serie/Teil 1/", "Audiobooks/Autor/Serie/Teil 2/"),
            books.map { it.key },
        )
        assertEquals(listOf(listOf(4L), listOf(1L, 2L), listOf(3L)), books.map { book -> book.files.map { it.id } })
        assertEquals("$base/Teil 1", books[1].folderPath)
        assertEquals("Teil 1", books[1].folderName)
    }

    @Test
    fun `only bare disc names count, and not directly in the root`() {
        assertEquals(1, LocalBookLayout.discNumber("CD 1"))
        assertEquals(2, LocalBookLayout.discNumber("cd02"))
        assertEquals(3, LocalBookLayout.discNumber("Disc 3"))
        assertEquals(4, LocalBookLayout.discNumber("Disk_4"))
        assertEquals(5, LocalBookLayout.discNumber("Teil 5"))
        assertNull(LocalBookLayout.discNumber("1 – Der Anfang"))
        assertNull(LocalBookLayout.discNumber("Teil 2 – Die Rückkehr"))

        val books = LocalBookLayout.group(
            listOf(
                row(1, "/storage/emulated/0/Audiobooks/CD 1/a.mp3"),
                row(2, "/storage/emulated/0/Audiobooks/Autor/Serie/1 – Anfang/a.mp3"),
                row(3, "/storage/emulated/0/Audiobooks/Autor/Serie/2 – Ende/a.mp3"),
            ),
        )
        assertEquals(
            listOf("Audiobooks/Autor/Serie/1 – Anfang/", "Audiobooks/Autor/Serie/2 – Ende/", "Audiobooks/CD 1/"),
            books.map { it.key },
        )
    }

    @Test
    fun `a book from disc folders takes its title without the disc`() {
        val base = "/storage/emulated/0/Audiobooks/Autor/Buch"
        val tagged = LocalBookLayout.group(
            listOf(
                row(1, "$base/CD 1/01.mp3", album = "Das Buch (CD 1)", track = 1),
                row(2, "$base/CD 2/01.mp3", album = "Das Buch (CD 2)", track = 1),
                row(3, "$base/CD 3/01.mp3", album = "Das Buch - CD 3", track = 1),
            ),
        ).single()
        // Without tags MediaStore reports the disc folder as album: then the book's folder decides.
        val untagged = LocalBookLayout.group(
            listOf(row(1, "$base/CD 1/01.mp3", album = "CD 1"), row(2, "$base/CD 2/01.mp3", album = "CD 2")),
        ).single()

        assertEquals("Das Buch", LocalBookLayout.bookTitle(tagged))
        assertEquals("Buch", LocalBookLayout.bookTitle(untagged))
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
            listOf(row(1, "/Audiobooks/B/Chapter 10.mp3"), row(2, "/Audiobooks/B/Chapter 2.mp3")),
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
        assertEquals("Chapter 1", LocalBookLayout.chapterTitle(null, "Chapter_1.mp3"))
    }

    @Test
    fun `single file takes its own title, a folder the shared album`() {
        val single = LocalBookFiles("Audiobooks/W.m4b", null, listOf(row(1, "/Audiobooks/W.m4b", title = "Wurst", album = "Wurst: Comedy", artist = "Kalpenstein")))
        val folder = LocalBookFiles(
            "Audiobooks/Ringe/",
            "Ringe",
            listOf(row(1, "/Audiobooks/Ringe/a.mp3", title = "1", album = "Tolkien: Ringe"), row(2, "/Audiobooks/Ringe/b.mp3", title = "2", album = "Tolkien: Ringe")),
        )
        val untagged = LocalBookFiles("Audiobooks/Empty/", "Empty", listOf(row(1, "/Audiobooks/Empty/a.mp3", album = "<unknown>", artist = "<unknown>")))

        assertEquals("Wurst", LocalBookLayout.bookTitle(single))
        assertEquals("Kalpenstein", LocalBookLayout.bookAuthor(single))
        assertEquals("Tolkien: Ringe", LocalBookLayout.bookTitle(folder))
        assertEquals("", LocalBookLayout.bookAuthor(untagged))
    }

    @Test
    fun `without tags the folder title counts, not what MediaStore fills in`() {
        // MediaStore: title = file name, album = folder name.
        val inFolder = LocalBookFiles(
            "Audiobooks/A/S/1 – Title/",
            "1 – Title",
            listOf(row(1, "/storage/emulated/0/Audiobooks/A/S/1 – Title/01.mp3", title = "01", album = "1 – Title")),
        )
        val inRoot = LocalBookFiles("Audiobooks/Book.mp3", null, listOf(row(2, "/storage/emulated/0/Audiobooks/Book.mp3", title = "Book", album = "Audiobooks")))

        assertEquals("Title", LocalBookLayout.bookTitle(inFolder, "Title"))
        assertEquals("1 – Title", LocalBookLayout.bookTitle(inFolder))
        assertEquals("Book", LocalBookLayout.bookTitle(inRoot))
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
