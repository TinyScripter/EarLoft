package de.kleinabersmart.earloft.core.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private val scope = TestScope()

    /** A book that runs with the test clock: position = start + elapsed time × speed, while it plays. */
    private inner class FakeTarget : SleepTimerTarget {
        override var volume = 1f
        var pauseCount = 0
        var playCount = 0
        var rewound = 0L
        var spoken = true
        var music = false
        var bookId = 1L
        var ends = listOf(60_000L, 120_000L, 300_000L)
        var speed = 1f
        private var positionAtMs = 0L
        private var since = 0L
        private var playing = true

        var position: Long
            get() = positionAtMs + if (playing) ((scope.currentTime - since) * speed).toLong() else 0L
            set(value) {
                positionAtMs = value
                since = scope.currentTime
            }

        override val isPlaying: Boolean get() = playing
        override val isSpoken: Boolean get() = spoken
        override val hasMusic: Boolean get() = music

        override fun pause() {
            position = position
            playing = false
            pauseCount++
        }

        override fun play() {
            position = position
            playing = true
            playCount++
        }

        /** Handover to the cast device: the queue is briefly empty, `isSpoken` stays (like `asSleepTimerTarget`). */
        var awaitsRemoteQueue = false

        override fun spokenSpot(): SpokenSpot? = when {
            spoken && !awaitsRemoteQueue -> SpokenSpot(bookId, position, speed, ends)
            music -> SpokenSpot(bookId, position, speed, ends.take(1), music = true)
            else -> null
        }

        override fun rewind(heardMs: Long) {
            rewound += heardMs
            position = (position - (heardMs * speed).toLong()).coerceAtLeast(0)
        }
    }

    private val target = FakeTarget()

    /** Deep sleep: real time keeps running, `delay` (uptime) stands still. */
    private var deepSleepMs = 0L
    private val timer = SleepTimer(now = { scope.currentTime + deepSleepMs }, scope = scope).apply { attach(target) }

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun `fadeVolume drops linearly to zero`() {
        assertEquals(1f, fadeVolume(SleepTimer.FADE_MS), 0.001f)
        assertEquals(0.5f, fadeVolume(SleepTimer.FADE_MS / 2), 0.001f)
        assertEquals(0f, fadeVolume(0), 0.001f)
        assertEquals(1f, fadeVolume(SleepTimer.FADE_MS * 3), 0.001f)
    }

    @Test
    fun `pauses on expiry and restores the volume`() {
        timer.start(60_000)
        scope.advanceTimeBy(59_999)
        assertEquals(0, target.pauseCount)
        assertTrue(timer.state.value != null)
        advance(2)
        assertEquals(1, target.pauseCount)
        assertEquals(1f, target.volume, 0f)
        assertNull(timer.state.value)
    }

    @Test
    fun `fades out in the last seconds`() {
        timer.start(60_000)
        scope.advanceTimeBy(30_000)
        assertEquals(1f, target.volume, 0f)
        advance(20_000)
        // 10 s before expiry: half volume.
        assertEquals(0.5f, target.volume, 0.02f)
    }

    @Test
    fun `cancelling prevents the pause and restores the volume`() {
        timer.start(60_000)
        scope.advanceTimeBy(50_000)
        timer.cancel()
        advance(60_000)
        assertEquals(0, target.pauseCount)
        assertEquals(1f, target.volume, 0f)
        assertNull(timer.state.value)
    }

    @Test
    fun `a new start replaces the running timer`() {
        timer.start(30_000)
        scope.advanceTimeBy(20_000)
        timer.start(60_000)
        advance(40_000)
        assertEquals(0, target.pauseCount)
        advance(30_000)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `without a player nothing happens`() {
        timer.detach()
        timer.start(60_000)
        assertNull(timer.state.value)
    }

    @Test
    fun `end of chapter stops just before the boundary`() {
        target.position = 10_000
        timer.startToEnd()
        scope.runCurrent()
        assertEquals(SleepTimerState(50_000, toEnd = true), timer.state.value)
        advance(49_000)
        assertEquals(0, target.pauseCount)
        advance(1_000)
        assertEquals(1, target.pauseCount)
        // Still in the chapter, not already in the next one.
        assertTrue(target.position in 59_000..59_999)
        assertNull(timer.state.value)
    }

    @Test
    fun `end of chapter takes the speed into account`() {
        target.speed = 2f
        target.position = 0
        timer.startToEnd()
        scope.runCurrent()
        assertEquals(30_000L, timer.state.value?.remainingMs)
        advance(30_000)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `seeking into another chapter falls asleep at its end`() {
        target.position = 10_000
        timer.startToEnd()
        advance(5_000)
        // Into the third chapter (120 s to 300 s).
        target.position = 200_000
        advance(1_000)
        assertEquals(99_000L, timer.state.value?.remainingMs)
        // Back into the first: its end again.
        target.position = 1_000
        advance(1_000)
        assertEquals(58_000L, timer.state.value?.remainingMs)
    }

    @Test
    fun `another book brings the start of its own chapter instead of the previous one`() {
        target.position = 10_000
        timer.startToEnd()
        advance(1_000)
        // A different book, in the middle of the third chapter (120 s to 300 s), without running past the boundary.
        target.bookId = 2
        target.position = 130_000
        advance(1_000)
        assertEquals(169_000L, timer.state.value?.remainingMs)
        // Back into the first chapter of this book: its end applies, not that of the third.
        target.position = 50_000
        advance(1_000)
        assertEquals(9_000L, timer.state.value?.remainingMs)
    }

    @Test
    fun `while paused, end of chapter does not count down and does not fade out`() {
        target.position = 50_000
        timer.startToEnd()
        advance(1_000)
        target.pause()
        val paused = timer.state.value?.remainingMs
        advance(30_000)
        assertEquals(paused, timer.state.value?.remainingMs)
        assertEquals(1f, target.volume, 0f)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `episode to the end followed by music ends the timer without a pause`() {
        target.ends = listOf(90_000)
        target.position = 0
        timer.startToEnd()
        advance(10_000)
        assertEquals(80_000L, timer.state.value?.remainingMs)
        target.spoken = false
        advance(2_000)
        assertNull(timer.state.value)
        assertEquals(0, target.pauseCount)
    }

    @Test
    fun `end of song pauses just before the next song`() {
        target.spoken = false
        target.music = true
        target.ends = listOf(200_000)
        target.position = 150_000
        timer.startToEnd()
        advance(10_000)
        assertEquals(40_000L, timer.state.value?.remainingMs)
        advance(40_000)
        assertNull(timer.state.value)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `episode to the end followed by music with a song ends the timer without a pause`() {
        target.ends = listOf(90_000)
        target.position = 0
        timer.startToEnd()
        advance(10_000)
        target.spoken = false
        target.music = true
        target.bookId = -1
        advance(2_000)
        assertNull(timer.state.value)
        assertEquals(0, target.pauseCount)
    }

    @Test
    fun `end of chapter waits out the empty queue during the handover to the cast device`() {
        target.position = 10_000
        timer.startToEnd()
        advance(5_000)
        target.awaitsRemoteQueue = true
        advance(7_000)
        assertTrue(timer.state.value != null)
        target.awaitsRemoteQueue = false
        advance(1_000)
        assertEquals(37_000L, timer.state.value?.remainingMs)
        assertEquals(0, target.pauseCount)
    }

    @Test
    fun `crossing the boundary into the next item counts as the end`() {
        target.ends = listOf(10_000)
        target.position = 9_000
        timer.startToEnd()
        scope.runCurrent()
        // The next episode is already loaded before the timer checks again.
        target.bookId = 2
        target.position = 0
        advance(1_000)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `shaking only shortly before expiry`() {
        timer.start(10 * 60_000L)
        advance(60_000)
        assertFalse(timer.shakeWindow.value)
        assertFalse(timer.extend())
        advance(8 * 60_000L + 30_000)
        assertTrue(timer.shakeWindow.value)
    }

    @Test
    fun `paused shortly before the end of chapter shaking does not help and the sensor rests`() {
        target.position = 20_000
        timer.startToEnd()
        advance(5_000)
        assertTrue(timer.shakeWindow.value)
        target.pause()
        advance(1_000)
        assertFalse(timer.shakeWindow.value)
        assertFalse(timer.extend())
        assertEquals(0L, target.rewound)
        // Played again: the window is open again.
        target.play()
        advance(1_000)
        assertTrue(timer.shakeWindow.value)
    }

    @Test
    fun `shaking before the fade-out does not rewind`() {
        target.position = 100_000
        timer.start(5 * 60_000L)
        advance(5 * 60_000L - 40_000)
        assertEquals(1f, target.volume, 0f)
        assertTrue(timer.extend())
        assertEquals(0L, target.rewound)
        assertEquals(100_000L + 5 * 60_000L - 40_000, target.position)
    }

    @Test
    fun `shaking during the fade-out restarts the duration and rewinds the quiet part`() {
        target.position = 100_000
        timer.start(5 * 60_000L)
        advance(5 * 60_000L - 5_000)
        assertTrue(target.volume < 1f)
        assertTrue(timer.extend())
        assertEquals(15_000L, target.rewound)
        assertEquals(1f, target.volume, 0f)
        scope.runCurrent()
        assertEquals(5 * 60_000L, timer.state.value?.remainingMs)
        assertEquals(0, target.pauseCount)
    }

    @Test
    fun `shaking after falling asleep resumes playback and rewinds`() {
        timer.start(60_000)
        advance(60_000)
        assertEquals(1, target.pauseCount)
        advance(60_000)
        assertTrue(timer.extend())
        assertEquals(1, target.playCount)
        assertEquals(SleepTimer.FADE_MS, target.rewound)
        scope.runCurrent()
        assertEquals(60_000L, timer.state.value?.remainingMs)
    }

    @Test
    fun `after two minutes shaking no longer helps`() {
        timer.start(60_000)
        advance(60_000)
        advance(SleepTimer.SHAKE_AFTER_MS)
        assertFalse(timer.shakeWindow.value)
        assertFalse(timer.extend())
        assertEquals(0, target.playCount)
    }

    @Test
    fun `after two minutes of deep sleep shaking does not help even if the window is still open`() {
        timer.start(60_000)
        advance(60_000)
        advance(30_000)
        // Screen off, the device sleeps for three hours: closing the window never got its turn.
        deepSleepMs += 3 * 60 * 60_000L
        assertTrue(timer.shakeWindow.value)
        assertFalse(timer.extend())
        assertFalse(timer.shakeWindow.value)
        assertEquals(0, target.playCount)
        assertEquals(0L, target.rewound)
    }

    @Test
    fun `shaking with end of chapter extends to the end of the next chapter`() {
        target.position = 30_000
        timer.startToEnd()
        advance(30_000)
        assertEquals(1, target.pauseCount)
        assertTrue(timer.extend())
        scope.runCurrent()
        // 20 s back and then up to 120 s.
        val remaining = timer.state.value?.remainingMs ?: 0
        assertTrue("$remaining", remaining in 79_000..81_000)
    }

    @Test
    fun `shaking in the last section extends by the last chosen duration`() {
        timer.start(30 * 60_000L)
        timer.cancel()
        target.ends = listOf(90_000)
        target.position = 60_000
        timer.startToEnd()
        advance(30_000)
        assertEquals(1, target.pauseCount)
        assertTrue(timer.extend())
        scope.runCurrent()
        assertEquals(SleepTimerState(30 * 60_000L, toEnd = false), timer.state.value)
    }

    @Test
    fun `a song in a new queue at the same position is another song`() {
        fun key(index: Int, mediaId: String) = musicSpot(index, mediaId, 0, 1f, 180_000, null)!!.bookId
        assertEquals(key(3, "song-a"), key(3, "song-a"))
        assertTrue(key(3, "song-a") != key(3, "song-b"))
        assertTrue(key(3, "song-a") != key(4, "song-a"))
        assertTrue(key(0, "song-a") < 0)
        assertTrue(key(Int.MAX_VALUE, "song-a") < 0)
    }

    @Test
    fun `end of song with repeat one stops when the song starts over between two steps`() {
        target.spoken = false
        target.music = true
        target.ends = listOf(200_000)
        target.position = 190_000
        timer.startToEnd()
        advance(8_000)
        assertEquals(2_000L, timer.state.value?.remainingMs)
        // The song repeats before the next step: the player is back at its start.
        target.position = 0
        advance(1_000)
        assertNull(timer.state.value)
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `another album started at the same position counts down the new song`() {
        target.spoken = false
        target.music = true
        target.bookId = musicSpot(2, "old", 0, 1f, 180_000, null)!!.bookId
        target.ends = listOf(180_000)
        target.position = 0
        timer.startToEnd()
        advance(10_000)
        assertEquals(170_000L, timer.state.value?.remainingMs)
        target.bookId = musicSpot(2, "new", 0, 1f, 360_000, null)!!.bookId
        target.ends = listOf(360_000)
        target.position = 0
        advance(1_000)
        assertEquals(359_000L, timer.state.value?.remainingMs)
        advance(170_000)
        assertEquals(0, target.pauseCount)
    }

    @Test
    fun `without the player's length the song length comes from the metadata`() {
        assertEquals(listOf(200_000L), musicSpot(0, "a", 0, 1f, androidx.media3.common.C.TIME_UNSET, 200_000)?.ends)
        assertEquals(listOf(150_000L), musicSpot(0, "a", 0, 1f, 150_000, 200_000)?.ends)
        assertNull(musicSpot(0, "a", 0, 1f, androidx.media3.common.C.TIME_UNSET, null))
        assertNull(musicSpot(0, "a", 0, 1f, androidx.media3.common.C.TIME_UNSET, 0))
    }

    @Test
    fun `until the end does not show the remaining time of the replaced timer while it waits`() {
        timer.start(60_000)
        advance(1_000)
        assertEquals(59_000L, timer.state.value?.remainingMs)
        target.awaitsRemoteQueue = true
        timer.startToEnd()
        advance(5_000)
        assertNull(timer.state.value)
    }

    @Test
    fun `a fixed duration shows its time right away`() {
        timer.start(60_000)
        assertEquals(SleepTimerState(60_000, toEnd = false), timer.state.value)
    }
}
