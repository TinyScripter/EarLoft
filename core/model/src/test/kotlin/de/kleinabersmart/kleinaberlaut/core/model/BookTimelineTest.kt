package de.kleinabersmart.kleinaberlaut.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTimelineTest {

    private val chapters = listOf(
        BookChapter(0, "Eins", 0, 10_000),
        BookChapter(1, "Zwei", 10_000, 40_000),
        BookChapter(2, "Drei", 40_000, 100_000),
    )

    private val singleFile = BookTimeline(100_000, listOf(BookTrack(0, "f", 0, 100_000)), chapters)

    private val folder = BookTimeline(
        durationMs = 90_000,
        tracks = listOf(BookTrack(0, "a", 0, 30_000), BookTrack(1, "b", 30_000, 30_000), BookTrack(2, "c", 60_000, 30_000)),
        chapters = emptyList(),
    )

    @Test
    fun `position is mapped to file and offset`() {
        assertEquals(TrackPosition(1, 5_000), folder.locate(35_000))
        assertEquals(TrackPosition(2, 0), folder.locate(60_000))
        assertEquals(TrackPosition(0, 0), folder.locate(-5))
        assertEquals(35_000L, folder.toBookPosition(1, 5_000))
    }

    @Test
    fun `position beyond the end stays in the last file`() {
        assertEquals(TrackPosition(2, 30_000), folder.locate(500_000))
    }

    @Test
    fun `chapter is found by position`() {
        assertEquals("Zwei", singleFile.chapterAt(10_000)?.title)
        assertEquals("Eins", singleFile.chapterAt(9_999)?.title)
        assertEquals("Drei", singleFile.chapterAt(99_000)?.title)
        assertEquals("Drei", singleFile.nextChapter(10_000)?.title)
        assertNull(singleFile.nextChapter(50_000))
    }

    @Test
    fun `previous chapter restarts the current one after a few seconds`() {
        assertEquals(10_000L, singleFile.previousChapterStart(20_000))
        assertEquals(0L, singleFile.previousChapterStart(11_000))
        assertEquals(0L, singleFile.previousChapterStart(1_000))
    }

    @Test
    fun `remaining time is divided by the speed`() {
        assertEquals(60_000L, singleFile.remainingBookMs(40_000))
        assertEquals(30_000L, singleFile.remainingBookMs(40_000, speed = 2f))
        assertEquals(30_000L, singleFile.remainingChapterMs(10_000))
        assertEquals(15_000L, singleFile.remainingChapterMs(10_000, speed = 2f))
    }

    @Test
    fun `a book without chapters has no chapter remaining time`() {
        assertFalse(folder.hasChapters)
        assertTrue(singleFile.hasChapters)
        assertNull(folder.remainingChapterMs(1_000))
        assertNull(folder.chapterAt(1_000))
    }
}
