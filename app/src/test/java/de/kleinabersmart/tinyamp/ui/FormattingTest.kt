package de.kleinabersmart.tinyamp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `bytes use SI units and a decimal comma`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1,5 KB", formatBytes(1_500))
        assertEquals("4,6 GB", formatBytes(4_600_000_000))
        assertEquals("250 MB", formatBytes(250_000_000))
    }

    @Test
    fun `negative sizes count as zero`() {
        assertEquals("0 B", formatBytes(-5))
    }
}
