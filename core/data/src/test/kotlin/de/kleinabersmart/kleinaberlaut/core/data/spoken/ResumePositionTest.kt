package de.kleinabersmart.kleinaberlaut.core.data.spoken

import de.kleinabersmart.kleinaberlaut.core.model.BookChapter
import de.kleinabersmart.kleinaberlaut.core.model.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePositionTest {
    private val now = 10_000_000L
    private val rule = ResumeRule(afterSeconds = 300, rewindSeconds = 15)
    private val chapters = listOf(
        BookChapter(0, "Eins", 0, 600_000),
        BookChapter(1, "Zwei", 600_000, 1_200_000),
    )

    private fun progress(positionMs: Long, pausedMs: Long, finished: Boolean = false) =
        BookProgress(positionMs, 1_200_000, finished, 1f, now - pausedMs, false)

    @Test
    fun `kurze Pause spult nicht zurueck`() {
        assertEquals(500_000L, resumePosition(progress(500_000, 60_000), now, rule))
    }

    @Test
    fun `lange Pause spult um die eingestellte Zeit zurueck`() {
        assertEquals(485_000L, resumePosition(progress(500_000, 301_000), now, rule))
    }

    @Test
    fun `Pause genau an der Schwelle spult nicht zurueck`() {
        assertEquals(500_000L, resumePosition(progress(500_000, 300_000), now, rule))
    }

    @Test
    fun `Zurueckspulen 0 ist aus`() {
        assertEquals(500_000L, resumePosition(progress(500_000, 9_000_000), now, ResumeRule(300, 0)))
    }

    @Test
    fun `Zurueckspulen geht nicht vor den Anfang`() {
        assertEquals(0L, resumePosition(progress(5_000, 9_000_000), now, rule))
    }

    @Test
    fun `beendet oder ohne Stand beginnt von vorn`() {
        assertEquals(0L, resumePosition(progress(1_190_000, 9_000_000, finished = true), now, rule))
        assertEquals(0L, resumePosition(null, now, rule))
    }

    @Test
    fun `kurz nach Kapitelanfang beendet startet das Kapitel neu, auch nach kurzer Pause`() {
        assertEquals(600_000L, resumePosition(progress(620_000, 1_000), now, rule, chapters, restartChapter = true))
    }

    @Test
    fun `Kapitelregel ist abschaltbar`() {
        assertEquals(620_000L, resumePosition(progress(620_000, 1_000), now, rule, chapters, restartChapter = false))
    }

    @Test
    fun `weiter als 30 Sekunden im Kapitel gilt die Rueckspulregel`() {
        assertEquals(700_000L, resumePosition(progress(700_000, 1_000), now, rule, chapters, restartChapter = true))
        assertEquals(685_000L, resumePosition(progress(700_000, 400_000), now, rule, chapters, restartChapter = true))
    }
}
