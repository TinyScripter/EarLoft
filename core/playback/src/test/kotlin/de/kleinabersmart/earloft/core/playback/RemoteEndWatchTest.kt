package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteEndWatchTest {

    private var now = 0L
    private val watch = RemoteEndWatch { now }

    @Test
    fun `stopping where the episode should have reached its end counts as ended`() {
        watch.playing(bookId = 7, isEpisode = true, positionMs = 3_588_000, durationMs = 3_600_000, speed = 1f)
        now += 12_000

        assertEquals(RemoteEndWatch.Ended(7, isEpisode = true, durationMs = 3_600_000), watch.ended())
    }

    @Test
    fun `stopping in the middle does not count`() {
        watch.playing(bookId = 7, isEpisode = true, positionMs = 1_000_000, durationMs = 3_600_000, speed = 1f)
        now += 15_000

        assertNull(watch.ended())
    }

    @Test
    fun `speed is taken into account`() {
        watch.playing(bookId = 7, isEpisode = false, positionMs = 3_570_000, durationMs = 3_600_000, speed = 2f)
        now += 12_000 // 24 s Inhalt: 6 s vor dem Ende

        assertEquals(7L, watch.ended()?.bookId)
    }

    @Test
    fun `a reset forgets the last observation`() {
        watch.playing(bookId = 7, isEpisode = true, positionMs = 3_599_000, durationMs = 3_600_000, speed = 1f)
        watch.reset()
        now += 5_000

        assertNull(watch.ended())
    }

    @Test
    fun `the end is reported only once`() {
        watch.playing(bookId = 7, isEpisode = true, positionMs = 3_599_000, durationMs = 3_600_000, speed = 1f)
        now += 2_000

        assertEquals(7L, watch.ended()?.bookId)
        assertNull(watch.ended())
    }

    @Test
    fun `without a known duration nothing ends`() {
        watch.playing(bookId = 7, isEpisode = true, positionMs = 3_599_000, durationMs = 0, speed = 1f)
        now += 60_000

        assertNull(watch.ended())
    }
}
