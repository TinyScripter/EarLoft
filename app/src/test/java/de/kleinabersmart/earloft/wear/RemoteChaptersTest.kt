package de.kleinabersmart.earloft.wear

import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.BookTrack
import de.kleinabersmart.earloft.core.wearsync.WearChapterDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChaptersTest {

    @Test
    fun `Kapitel gehen in Buchzeit und Reihenfolge zur Uhr, nicht abrufbare markiert`() {
        val timeline = BookTimeline(
            durationMs = 90_000,
            tracks = listOf(BookTrack(0, "a", 0, 45_000), BookTrack(1, "b", 45_000, 45_000)),
            chapters = listOf(
                BookChapter(1, "Zwei", 30_000, 90_000, unavailable = true),
                BookChapter(0, "Eins", 0, 30_000),
            ),
        )

        val dto = remoteChapters(7, timeline)

        assertEquals(7L, dto.bookId)
        assertEquals(
            listOf(WearChapterDto("Eins", 0, 30_000), WearChapterDto("Zwei", 30_000, 90_000, unavailable = true)),
            dto.chapters,
        )
    }

    @Test
    fun `ohne Zeitachse eine leere Liste`() {
        assertTrue(remoteChapters(7, null).chapters.isEmpty())
    }
}
