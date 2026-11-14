package de.kleinabersmart.earloft.ui.spoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonRunsTest {

    @Test
    fun `one season or none needs no headers`() {
        assertNull(seasonRuns(listOf(1, 1, null)) { it })
        assertNull(seasonRuns(listOf<Int?>(null, null)) { it })
    }

    @Test
    fun `runs follow the list order`() {
        val items = listOf("a" to 2, "b" to 2, "c" to null, "d" to 1, "e" to 2)
        val runs = seasonRuns(items) { it.second }!!
        assertEquals(listOf(2, null, 1, 2), runs.map { it.first })
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d"), listOf("e")), runs.map { run -> run.second.map { it.first } })
    }
}
