package keinabersmart.kleinaberlaut.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteHandOverTest {

    private val episode = PlayerState(spoken = SpokenEntry(bookId = 7, trackIndex = 0, title = "Folge", author = "Feed"), isPlaying = true)
    private val emptyRemote = PlayerState(isRemote = true)

    @Test
    fun `keeps the local state while the receiver has not reported the queue yet`() {
        val handOver = RemoteHandOver()
        handOver.update(PlayerState(), episode, queueEmpty = false, positionMs = 60_000, nowMs = 1_000)

        val shown = handOver.update(episode, emptyRemote, queueEmpty = true, positionMs = 0, nowMs = 3_000)

        assertEquals(episode.copy(isRemote = true), shown)
        // Läuft vom letzten bekannten Stand weiter, statt auf 0 zu springen.
        assertEquals(63_000L, handOver.positionMs(nowMs = 4_000))
    }

    @Test
    fun `stays held across further empty updates and lets go once the queue arrives`() {
        val handOver = RemoteHandOver()
        handOver.update(PlayerState(), episode, queueEmpty = false, positionMs = 0, nowMs = 0)
        val held = handOver.update(episode, emptyRemote, queueEmpty = true, positionMs = 0, nowMs = 0)
        assertEquals(held, handOver.update(held, emptyRemote, queueEmpty = true, positionMs = 0, nowMs = 0))

        val loaded = episode.copy(isRemote = true, isPlaying = false)
        assertEquals(loaded, handOver.update(held, loaded, queueEmpty = false, positionMs = 61_000, nowMs = 500))
        assertNull(handOver.positionMs(nowMs = 600))
    }

    @Test
    fun `paused position does not run on`() {
        val handOver = RemoteHandOver()
        val paused = episode.copy(isPlaying = false)
        handOver.update(PlayerState(), paused, queueEmpty = false, positionMs = 60_000, nowMs = 0)
        handOver.update(paused, emptyRemote, queueEmpty = true, positionMs = 0, nowMs = 0)

        assertEquals(60_000L, handOver.positionMs(nowMs = 5_000))
    }

    @Test
    fun `a queue that empties while already casting is shown as empty`() {
        val handOver = RemoteHandOver()
        val casting = episode.copy(isRemote = true)

        assertEquals(emptyRemote, handOver.update(casting, emptyRemote, queueEmpty = true, positionMs = 0, nowMs = 0))
    }

    @Test
    fun `back on the phone the actual state is shown`() {
        val handOver = RemoteHandOver()
        handOver.update(PlayerState(), episode, queueEmpty = false, positionMs = 0, nowMs = 0)
        val held = handOver.update(episode, emptyRemote, queueEmpty = true, positionMs = 0, nowMs = 0)

        val local = episode.copy(isPlaying = false)
        assertEquals(local, handOver.update(held, local, queueEmpty = false, positionMs = 0, nowMs = 0))
    }
}
