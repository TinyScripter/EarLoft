package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SkippedSilenceTest {

    private val silence = SkippedSilence().apply { sampleRate = 44_100 }

    @Test
    fun `counts the frames skipped since the last reading as content time`() {
        silence.onFrameCount(0)
        silence.onFrameCount(44_100)
        silence.onFrameCount(44_100)
        silence.onFrameCount(66_150)
        assertEquals(1_500L, silence.drainMs())
        assertEquals(0L, silence.drainMs())
    }

    @Test
    fun `a flush starts the counter again from zero without losing anything`() {
        silence.onFrameCount(88_200)
        // Seeked or next file: the processor counts from 0.
        silence.onFrameCount(22_050)
        assertEquals(2_500L, silence.drainMs())
    }

    @Test
    fun `remainders under one millisecond carry over`() {
        silence.sampleRate = 48_000
        silence.onFrameCount(24) // 0.5 ms
        assertEquals(0L, silence.drainMs())
        silence.onFrameCount(48)
        assertEquals(1L, silence.drainMs())
    }

    @Test
    fun `nothing is counted before the format is known`() {
        silence.sampleRate = 0
        silence.onFrameCount(44_100)
        assertEquals(0L, silence.drainMs())
    }
}
