package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesTest {

    @Test
    fun `splits name and sequence`() {
        assertEquals(listOf(SeriesRef("Carl Mørck", 10.0)), parseSeries("Carl Mørck #10"))
        assertEquals(listOf(SeriesRef("Magic 2.0", 2.5)), parseSeries("Magic 2.0 #2.5"))
    }

    @Test
    fun `name without sequence stays whole`() {
        assertEquals(listOf(SeriesRef("#meinAudibleOriginal", null)), parseSeries("#meinAudibleOriginal"))
        assertEquals(listOf(SeriesRef("Daniel und Aylin", null)), parseSeries("Daniel und Aylin"))
    }

    @Test
    fun `several series are separated after the sequence`() {
        assertEquals(
            listOf(SeriesRef("Harry Hole, Band", 14.0), SeriesRef("Nesbø", 3.0)),
            parseSeries("Harry Hole, Band #14, Nesbø #3"),
        )
    }

    @Test
    fun `blank is empty`() {
        assertEquals(emptyList<SeriesRef>(), parseSeries(""))
    }
}
