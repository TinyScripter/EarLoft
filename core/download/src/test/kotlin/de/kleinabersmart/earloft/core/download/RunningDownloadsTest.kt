package de.kleinabersmart.earloft.core.download

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningDownloadsTest {

    @After
    fun tearDown() {
        listOf("a", "b").forEach(RunningDownloads::release)
    }

    @Test
    fun `parallel downloads count against each other`() {
        assertTrue(RunningDownloads.claim("a", bytes = 600, available = 1_000, reserve = 100))
        assertFalse(RunningDownloads.claim("b", bytes = 400, available = 1_000, reserve = 100))
        assertTrue(RunningDownloads.claim("b", bytes = 300, available = 1_000, reserve = 100))
    }

    @Test
    fun `what a download has written no longer counts as claimed`() {
        assertTrue(RunningDownloads.claim("a", bytes = 600, available = 1_000, reserve = 100))
        RunningDownloads.update("a", 100)
        assertTrue(RunningDownloads.claim("b", bytes = 400, available = 1_000, reserve = 100))
    }

    @Test
    fun `a finished download frees its claim and claiming again replaces the old one`() {
        assertTrue(RunningDownloads.claim("a", bytes = 600, available = 1_000, reserve = 100))
        assertTrue(RunningDownloads.claim("a", bytes = 800, available = 1_000, reserve = 100))
        RunningDownloads.release("a")
        assertTrue(RunningDownloads.claim("b", bytes = 900, available = 1_000, reserve = 100))
    }
}
