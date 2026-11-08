package de.kleinabersmart.earloft.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EpisodeDownloadProgressTest {

    private val progress = EpisodeDownloadProgress()

    @Test
    fun `progress is rounded down to whole percent`() {
        progress.report(1, 1_239, 10_000)
        assertEquals(0.12f, progress.fractions.value.getValue(1), 0f)
        progress.report(1, 10_000, 10_000)
        assertEquals(1f, progress.fractions.value.getValue(1), 0f)
    }

    @Test
    fun `the same percent does not emit a new map`() {
        progress.report(1, 1_200, 10_000)
        val before = progress.fractions.value
        progress.report(1, 1_299, 10_000)
        assertSame(before, progress.fractions.value)
    }

    @Test
    fun `unknown size stays unknown`() {
        progress.report(1, 500, null)
        assertEquals(EpisodeDownloadProgress.UNKNOWN, progress.fractions.value.getValue(1), 0f)
        progress.report(1, 500, 0)
        assertEquals(EpisodeDownloadProgress.UNKNOWN, progress.fractions.value.getValue(1), 0f)
    }
}
