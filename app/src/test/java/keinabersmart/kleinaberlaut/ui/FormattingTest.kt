package keinabersmart.kleinaberlaut.ui

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

    @Test
    fun `remaining time shows seconds only below one hour`() {
        assertEquals("0:00", formatRemaining(0))
        assertEquals("29:57", formatRemaining(1_797_000))
        assertEquals("59:59", formatRemaining(3_599_000))
        assertEquals("1:00", formatRemaining(3_600_000))
        assertEquals("2:30", formatRemaining(8_997_000))
        assertEquals("1:01", formatRemaining(3_630_000))
    }
}
