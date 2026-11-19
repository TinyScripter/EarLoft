package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun `timestamps make lyrics synced and sorted`() {
        val lyrics = LrcParser.parse("[ar:Band]\n[00:12.50]Second\n[00:01.5]First\n[01:02]Third")

        assertEquals(
            Lyrics.Synced(
                listOf(LyricLine(1_500, "First"), LyricLine(12_500, "Second"), LyricLine(62_000, "Third")),
            ),
            lyrics,
        )
    }

    @Test
    fun `several timestamps repeat the line`() {
        val lyrics = LrcParser.parse("[00:05.00][00:20.00]Refrain")

        assertEquals(Lyrics.Synced(listOf(LyricLine(5_000, "Refrain"), LyricLine(20_000, "Refrain"))), lyrics)
    }

    @Test
    fun `offset tag shifts all lines`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(0, "one"), LyricLine(1_500, "two"))),
            LrcParser.parse("[offset:+500]\n[00:00.20]one\n[00:02.00]two"),
        )
        assertEquals(Lyrics.Synced(listOf(LyricLine(2_300, "two"))), LrcParser.parse("[offset: -300]\n[00:02.00]two"))
    }

    @Test
    fun `text without timestamps is plain`() {
        assertEquals(Lyrics.Plain("Line 1\n\nLine 2"), LrcParser.parse("Line 1\r\n\r\nLine 2\n"))
    }

    @Test
    fun `meta lines are dropped from plain text and blank input is none`() {
        assertEquals(Lyrics.Plain("Text"), LrcParser.parse("[ti:Title]\nText"))
        assertEquals(Lyrics.None, LrcParser.parse("  \n "))
    }
}
