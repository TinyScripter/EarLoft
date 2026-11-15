package de.kleinabersmart.earloft.core.datasource.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordnernamen, Begleitdateien und `metadata.opf` lokaler Bücher (`books_improvements.md` M7). */
class LocalBookMetadataTest {

    @Test
    fun `folder names give author, series, volume, year and narrator`() {
        assertEquals(
            FolderMetadata(author = "Terry Pratchett", series = "Scheibenwelt", sequence = "1", title = "Die Farben der Magie"),
            FolderMetadata.parse("Audiobooks/Terry Pratchett/Scheibenwelt/1 – Die Farben der Magie/"),
        )
        assertEquals("Scheibenwelt #1", FolderMetadata.parse("Audiobooks/Terry Pratchett/Scheibenwelt/01. Die Farben der Magie/").seriesEntry)
        assertEquals(
            FolderMetadata(author = "A", series = "S", sequence = "2.5", title = "Titel", narrator = "Rufus Beck", year = 1999),
            FolderMetadata.parse("Audiobooks/A/S/Band 2,5 - 1999 - Titel {Rufus Beck}/"),
        )
        // Ohne Serienordner ist eine Zahl vorn Teil des Titels.
        assertEquals(FolderMetadata(author = "George Orwell", title = "1984"), FolderMetadata.parse("Audiobooks/George Orwell/1984/"))
        assertEquals(FolderMetadata(author = "A", title = "1 - Titel"), FolderMetadata.parse("Audiobooks/A/1 - Titel/"))
        assertEquals(FolderMetadata(title = "Buch"), FolderMetadata.parse("Audiobooks/Buch/"))
        // Eine Datei direkt in „Audiobooks“ hat keinen Ordner.
        assertEquals(FolderMetadata(), FolderMetadata.parse("Audiobooks/Buch.m4b"))
        assertEquals("S", FolderMetadata.parse("Audiobooks/A/S/Ohne Nummer/").seriesEntry)
        // Ein Band mit Buchstaben (Zwischenband).
        assertEquals(
            FolderMetadata(author = "A", series = "S", sequence = "2a", title = "Titel"),
            FolderMetadata.parse("Audiobooks/A/S/02a – Titel/"),
        )
    }

    private fun file(name: String, size: Long = 1) = SideFile(name, "content://x/$name", size, 0)

    @Test
    fun `sidecars prefer a named cover and metadata opf`() {
        val picked = BookSidecars.pick(
            listOf(file("01.mp3"), file("back.jpg"), file("Folder.JPG"), file("cover.png"), file("Desc.txt"), file("reader.txt"), file("x.opf"), file("metadata.opf")),
        )
        assertEquals("cover.png", picked.cover?.name)
        assertEquals("Desc.txt", picked.description?.name)
        assertEquals("reader.txt", picked.reader?.name)
        assertEquals("metadata.opf", picked.opf?.name)

        val other = BookSidecars.pick(listOf(file("b.webp"), file("a.jpg"), file("buch.opf")))
        assertEquals("a.jpg", other.cover?.name)
        assertEquals("buch.opf", other.opf?.name)
        assertNull(other.description)
    }

    @Test
    fun `the fingerprint changes with the sidecars, not with other files`() {
        val base = BookSidecars.pick(listOf(file("01.mp3"), file("desc.txt")))
        assertEquals(base.fingerprint, BookSidecars.pick(listOf(file("02.mp3"), file("desc.txt"))).fingerprint)
        assertFalse(base.fingerprint == BookSidecars.pick(listOf(file("desc.txt", size = 2))).fingerprint)
        assertFalse(base.fingerprint == BookSidecars.pick(listOf(file("desc.txt"), file("cover.jpg"))).fingerprint)
    }

    @Test
    fun `calibre opf with roles, series and isbn`() {
        val opf = OpfMetadata.parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
              <metadata>
                <dc:title>Die Farben der Magie</dc:title>
                <dc:creator opf:role="aut" opf:file-as="Pratchett, Terry">Terry Pratchett</dc:creator>
                <dc:creator opf:role="nrt">Rufus Beck</dc:creator>
                <dc:creator opf:role="trl">Andreas Brandhorst</dc:creator>
                <dc:description>&lt;p&gt;Rincewind &amp;amp; Zweiblum&lt;/p&gt;</dc:description>
                <dc:publisher>Der Hörverlag</dc:publisher>
                <dc:date>1992-05-01T00:00:00+00:00</dc:date>
                <dc:language>de</dc:language>
                <dc:subject>Fantasy</dc:subject>
                <dc:subject>Humor</dc:subject>
                <dc:identifier opf:scheme="ISBN">978-3-89940-123-4</dc:identifier>
                <dc:identifier opf:scheme="calibre">1234</dc:identifier>
                <meta name="calibre:series" content="Scheibenwelt"/>
                <meta name="calibre:series_index" content="1.0"/>
              </metadata>
            </package>
            """.trimIndent(),
        )!!
        assertEquals("Die Farben der Magie", opf.title)
        assertEquals(listOf("Terry Pratchett"), opf.authors)
        assertEquals(listOf("Rufus Beck"), opf.narrators)
        assertEquals("<p>Rincewind &amp; Zweiblum</p>", opf.description)
        assertEquals("Der Hörverlag", opf.publisher)
        assertEquals(1992, opf.year)
        assertEquals("de", opf.language)
        assertEquals(listOf("Fantasy", "Humor"), opf.genres)
        assertEquals("9783899401234", opf.isbn)
        assertEquals("Scheibenwelt #1", opf.seriesEntry)
    }

    @Test
    fun `epub 3 opf with refines`() {
        val opf = OpfMetadata.parse(
            """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title id="t1">Titel</dc:title>
                <dc:title id="t2">Der Untertitel</dc:title>
                <meta refines="#t2" property="title-type">subtitle</meta>
                <dc:creator id="c1">Anna Autor</dc:creator>
                <dc:creator id="c2">Sam Sprecher</dc:creator>
                <meta refines="#c2" property="role" scheme="marc:relators">nrt</meta>
                <meta property="belongs-to-collection" id="s1">Die Reihe</meta>
                <meta refines="#s1" property="group-position">2.5</meta>
                <dc:identifier>urn:isbn:3899401234</dc:identifier>
              </metadata>
            </package>
            """.trimIndent(),
        )!!
        assertEquals("Titel", opf.title)
        assertEquals("Der Untertitel", opf.subtitle)
        assertEquals(listOf("Anna Autor"), opf.authors)
        assertEquals(listOf("Sam Sprecher"), opf.narrators)
        assertEquals("Die Reihe #2.5", opf.seriesEntry)
        assertEquals("3899401234", opf.isbn)
    }

    @Test
    fun `broken opf is ignored`() {
        assertNull(OpfMetadata.parse("<package><metadata><dc:title>kaputt"))
        assertNull(OpfMetadata.parse("kein xml"))
    }

    @Test
    fun `document ids follow the storage paths that MediaStore reports`() {
        assertEquals("primary:Audiobooks/A/B", LocalBookFolders.documentId("/storage/emulated/0/Audiobooks/A/B"))
        assertEquals("primary:Audiobooks/A", LocalBookFolders.documentId("/sdcard/Audiobooks/A/"))
        assertEquals("1A2B-3C4D:Audiobooks/A", LocalBookFolders.documentId("/storage/1A2B-3C4D/Audiobooks/A"))
        assertNull(LocalBookFolders.documentId("/data/user/0/x/Audiobooks/A"))

        assertTrue(LocalBookFolders.covers("primary:Audiobooks", "primary:Audiobooks/A/B"))
        assertTrue(LocalBookFolders.covers("primary:audiobooks", "primary:Audiobooks/A"))
        assertTrue(LocalBookFolders.covers("primary:", "primary:Audiobooks/A"))
        assertFalse(LocalBookFolders.covers("primary:Audiobooks", "primary:AudiobooksAlt/A"))
        assertFalse(LocalBookFolders.covers("1A2B-3C4D:Audiobooks", "primary:Audiobooks/A"))
    }
}
