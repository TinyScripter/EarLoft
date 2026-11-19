package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePositionTest {
    private val now = 10_000_000L
    private val rule = ResumeRule(afterSeconds = 300, rewindSeconds = 15)
    private val chapters = listOf(
        BookChapter(0, "One", 0, 600_000),
        BookChapter(1, "Two", 600_000, 1_200_000),
    )

    private fun progress(positionMs: Long, pausedMs: Long, finished: Boolean = false) =
        BookProgress(positionMs, 1_200_000, finished, 1f, now - pausedMs, false)

    @Test
    fun `a short pause does not rewind`() {
        assertEquals(500_000L, resumePosition(progress(500_000, 60_000), now, rule))
    }

    @Test
    fun `a long pause rewinds by the configured time`() {
        assertEquals(485_000L, resumePosition(progress(500_000, 301_000), now, rule))
    }

    @Test
    fun `a pause exactly at the threshold does not rewind`() {
        assertEquals(500_000L, resumePosition(progress(500_000, 300_000), now, rule))
    }

    @Test
    fun `rewinding by 0 is off`() {
        assertEquals(500_000L, resumePosition(progress(500_000, 9_000_000), now, ResumeRule(300, 0)))
    }

    @Test
    fun `rewinding does not go before the beginning`() {
        assertEquals(0L, resumePosition(progress(5_000, 9_000_000), now, rule))
    }

    @Test
    fun `finished or without progress starts from the beginning`() {
        assertEquals(0L, resumePosition(progress(1_190_000, 9_000_000, finished = true), now, rule))
        assertEquals(0L, resumePosition(null, now, rule))
    }

    @Test
    fun `stopped shortly after a chapter start restarts the chapter, even after a short pause`() {
        assertEquals(600_000L, resumePosition(progress(620_000, 1_000), now, rule, chapters, restartChapter = true))
    }

    @Test
    fun `the chapter rule can be switched off`() {
        assertEquals(620_000L, resumePosition(progress(620_000, 1_000), now, rule, chapters, restartChapter = false))
    }

    @Test
    fun `more than 30 seconds into the chapter the rewind rule applies`() {
        assertEquals(700_000L, resumePosition(progress(700_000, 1_000), now, rule, chapters, restartChapter = true))
        assertEquals(685_000L, resumePosition(progress(700_000, 400_000), now, rule, chapters, restartChapter = true))
    }
}
