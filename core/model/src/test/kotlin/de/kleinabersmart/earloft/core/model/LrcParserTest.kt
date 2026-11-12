package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun `timestamps make lyrics synced and sorted`() {
        val lyrics = LrcParser.parse("[ar:Band]\n[00:12.50]Zweite\n[00:01.5]Erste\n[01:02]Dritte")

        assertEquals(
            Lyrics.Synced(
                listOf(LyricLine(1_500, "Erste"), LyricLine(12_500, "Zweite"), LyricLine(62_000, "Dritte")),
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
            Lyrics.Synced(listOf(LyricLine(0, "eins"), LyricLine(1_500, "zwei"))),
            LrcParser.parse("[offset:+500]\n[00:00.20]eins\n[00:02.00]zwei"),
        )
        assertEquals(Lyrics.Synced(listOf(LyricLine(2_300, "zwei"))), LrcParser.parse("[offset: -300]\n[00:02.00]zwei"))
    }

    @Test
    fun `text without timestamps is plain`() {
        assertEquals(Lyrics.Plain("Zeile 1\n\nZeile 2"), LrcParser.parse("Zeile 1\r\n\r\nZeile 2\n"))
    }

    @Test
    fun `meta lines are dropped from plain text and blank input is none`() {
        assertEquals(Lyrics.Plain("Text"), LrcParser.parse("[ti:Titel]\nText"))
        assertEquals(Lyrics.None, LrcParser.parse("  \n "))
    }
}
