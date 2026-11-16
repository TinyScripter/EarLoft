package de.kleinabersmart.earloft.wear.ui

import de.kleinabersmart.earloft.core.wearsync.WearChapterDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteChaptersDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteMediaKind
import de.kleinabersmart.earloft.core.wearsync.WearRemoteStateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChaptersTest {

    private val book = WearRemoteStateDto(hasMedia = true, kind = WearRemoteMediaKind.BOOK, bookId = 7)
    private val chapters = WearRemoteChaptersDto(
        bookId = 7,
        chapters = listOf(WearChapterDto("Eins", 0, 30_000), WearChapterDto("Zwei", 30_000, 90_000, unavailable = true)),
    )

    @Test
    fun `chapters of the playing book are shown`() {
        val shown = chaptersOf(book, chapters)

        assertEquals(listOf("Eins", "Zwei"), shown.map { it.title })
        assertEquals(listOf(0, 1), shown.map { it.index })
        assertEquals(listOf(false, true), shown.map { it.unavailable })
    }

    @Test
    fun `chapters of another book, music or no state are not shown`() {
        assertTrue(chaptersOf(book.copy(bookId = 8), chapters).isEmpty())
        assertTrue(chaptersOf(WearRemoteStateDto(hasMedia = true, kind = WearRemoteMediaKind.MUSIC), chapters).isEmpty())
        assertTrue(chaptersOf(book.copy(hasMedia = false), chapters).isEmpty())
        assertTrue(chaptersOf(null, chapters).isEmpty())
        assertTrue(chaptersOf(book, null).isEmpty())
    }

    @Test
    fun `the current chapter follows the position`() {
        val shown = chaptersOf(book, chapters)

        assertEquals("Eins", chapterAt(shown, 0)?.title)
        assertEquals("Eins", chapterAt(shown, 29_999)?.title)
        assertEquals("Zwei", chapterAt(shown, 30_000)?.title)
        assertNull(chapterAt(emptyList(), 10_000))
    }
}
