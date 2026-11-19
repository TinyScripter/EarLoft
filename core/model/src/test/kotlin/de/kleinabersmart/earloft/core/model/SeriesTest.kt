package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesTest {

    @Test
    fun `splits name and sequence`() {
        assertEquals(listOf(SeriesRef("Carl Mørck", 10.0, "10")), parseSeries("Carl Mørck #10"))
        assertEquals(listOf(SeriesRef("Magic 2.0", 2.5, "2.5")), parseSeries("Magic 2.0 #2.5"))
    }

    @Test
    fun `name without sequence stays whole`() {
        assertEquals(listOf(SeriesRef("#meinAudibleOriginal", null)), parseSeries("#meinAudibleOriginal"))
        assertEquals(listOf(SeriesRef("Daniel und Aylin", null)), parseSeries("Daniel und Aylin"))
        assertEquals(listOf(SeriesRef("Saga #", null)), parseSeries("Saga #"))
    }

    @Test
    fun `several series are separated after the sequence`() {
        assertEquals(
            listOf(SeriesRef("Harry Hole, Band", 14.0, "14"), SeriesRef("Nesbø", 3.0, "3")),
            parseSeries("Harry Hole, Band #14, Nesbø #3"),
        )
    }

    @Test
    fun `a volume that is not just a number still belongs to the series`() {
        val (twoA, world) = parseSeries("Saga #2a, World #1")
        assertEquals("Saga", twoA.name)
        assertEquals("2a", twoA.volume)
        assertEquals(SeriesRef("World", 1.0, "1"), world)

        val range = parseSeries("Saga #1-3").single()
        assertEquals("Saga", range.name)
        assertEquals("1-3", range.volume)

        assertEquals(listOf(SeriesRef("Saga", 1.5, "1,5")), parseSeries("Saga #1,5"))
        assertEquals(listOf(SeriesRef("Saga", null, "IV")), parseSeries("Saga #IV"))
        // The volume is everything after the last " #".
        assertEquals(listOf(SeriesRef("Foo #Bar", 3.0, "3")), parseSeries("Foo #Bar #3"))
    }

    @Test
    fun `a decimal comma does not separate series`() {
        assertEquals(
            listOf(SeriesRef("Saga", 1.5, "1,5"), SeriesRef("World", 2.0, "2")),
            parseSeries("Saga #1,5, World #2"),
        )
    }

    @Test
    fun `volumes with a suffix sort right after their number`() {
        val order = listOf("3", "2a", "1-3", "2", "1", "1,5").sortedBy { volumeSequence(it) }
        assertEquals(listOf("1", "1-3", "1,5", "2", "2a", "3"), order)
        assertTrue(volumeSequence("2a")!! > volumeSequence("2")!!)
    }

    @Test
    fun `blank is empty`() {
        assertEquals(emptyList<SeriesRef>(), parseSeries(""))
    }
}
