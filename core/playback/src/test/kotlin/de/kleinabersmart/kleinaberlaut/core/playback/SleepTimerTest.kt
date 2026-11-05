package de.kleinabersmart.kleinaberlaut.core.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private class FakeTarget : SleepTimerTarget {
        override var volume = 1f
        var pauseCount = 0
        override fun pause() {
            pauseCount++
        }
    }

    private val scope = TestScope()
    private val target = FakeTarget()
    private val timer = SleepTimer(now = { scope.currentTime }, scope = scope).apply { attach(target) }

    @Test
    fun `fadeVolume sinkt linear auf null`() {
        assertEquals(1f, fadeVolume(SleepTimer.FADE_MS), 0.001f)
        assertEquals(0.5f, fadeVolume(SleepTimer.FADE_MS / 2), 0.001f)
        assertEquals(0f, fadeVolume(0), 0.001f)
        assertEquals(1f, fadeVolume(SleepTimer.FADE_MS * 3), 0.001f)
    }

    @Test
    fun `pausiert nach Ablauf und stellt die Lautstaerke zurueck`() {
        timer.start(60_000)
        scope.advanceTimeBy(59_999)
        assertEquals(0, target.pauseCount)
        assertTrue(timer.endsAtMs.value != null)
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(1, target.pauseCount)
        assertEquals(1f, target.volume, 0f)
        assertNull(timer.endsAtMs.value)
    }

    @Test
    fun `blendet in den letzten Sekunden aus`() {
        timer.start(60_000)
        scope.advanceTimeBy(30_000)
        assertEquals(1f, target.volume, 0f)
        scope.advanceTimeBy(20_000)
        scope.runCurrent()
        // 10 s vor Ablauf: halbe Lautstärke.
        assertEquals(0.5f, target.volume, 0.02f)
    }

    @Test
    fun `Abbrechen verhindert die Pause und setzt die Lautstaerke zurueck`() {
        timer.start(60_000)
        scope.advanceTimeBy(50_000)
        timer.cancel()
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(0, target.pauseCount)
        assertEquals(1f, target.volume, 0f)
        assertNull(timer.endsAtMs.value)
    }

    @Test
    fun `neuer Start ersetzt den laufenden Timer`() {
        timer.start(30_000)
        scope.advanceTimeBy(20_000)
        timer.start(60_000)
        scope.advanceTimeBy(40_000)
        scope.runCurrent()
        assertEquals(0, target.pauseCount)
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        assertEquals(1, target.pauseCount)
    }

    @Test
    fun `ohne Player passiert nichts`() {
        timer.detach()
        timer.start(60_000)
        assertNull(timer.endsAtMs.value)
    }
}
