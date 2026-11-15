package de.kleinabersmart.earloft.core.data.repository

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryWindowTest {

    @Test
    fun `the start of a period is calculated again and again`() = runBlocking {
        val period = 90L * 24 * 60 * 60 * 1000
        val before = System.currentTimeMillis()

        val starts = LibraryRepository.windowStarts(period, refreshMs = 30).take(3).toList()

        assertEquals(3, starts.size)
        assertTrue(starts.first() >= before - period)
        assertEquals(starts.sorted(), starts)
        // Jeder Wert liegt später als der vorige: Es wird neu gerechnet statt einmal gemerkt.
        assertTrue(starts.last() >= starts.first() + 30)
    }
}
