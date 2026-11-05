package de.kleinabersmart.kleinaberlaut.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGainTest {

    @Test
    fun `gain in dB becomes a linear factor`() {
        assertEquals(0.5012f, ReplayGain(trackGainDb = -6f).linearFactor(useAlbum = false), 1e-4f)
        assertEquals(0.3162f, ReplayGain(trackGainDb = -6f).linearFactor(useAlbum = false, preAmpDb = -4f), 1e-4f)
    }

    @Test
    fun `album mode falls back to track gain and vice versa`() {
        val trackOnly = ReplayGain(trackGainDb = -6f)
        val albumOnly = ReplayGain(albumGainDb = -6f)
        assertEquals(trackOnly.linearFactor(useAlbum = false), trackOnly.linearFactor(useAlbum = true))
        assertEquals(albumOnly.linearFactor(useAlbum = true), albumOnly.linearFactor(useAlbum = false))
    }

    @Test
    fun `peak of the used gain prevents clipping`() {
        val rg = ReplayGain(trackGainDb = 6f, trackPeak = 0.8f, albumGainDb = 6f, albumPeak = 0.5f)
        assertEquals(1.25f, rg.linearFactor(useAlbum = false), 1e-4f)
        assertEquals(1.9953f, rg.linearFactor(useAlbum = true), 1e-4f)
    }

    @Test
    fun `no boost without peak, no change without values`() {
        assertEquals(1f, ReplayGain(trackGainDb = 3f).linearFactor(useAlbum = false))
        assertEquals(1f, ReplayGain().linearFactor(useAlbum = false, preAmpDb = 6f))
    }

    @Test
    fun `r128 gain is converted to replaygain level`() {
        assertEquals(-1f, ReplayGain.r128ToReplayGainDb(-1536), 1e-4f)
    }
}
