package de.kleinabersmart.earloft.ui.player

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerPageChangeTest {

    @Test
    fun `every later page brings the mini player back, not the first one`() = runBlocking {
        val pages = flowOf(null, 0, 0, 2, 3, 3, 0).laterPages().toList()

        assertEquals(listOf(2, 3, 0), pages)
    }
}
