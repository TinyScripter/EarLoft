package de.kleinabersmart.earloft.core.wearsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRemoteTest {

    private val playing = WearRemoteStateDto(hasMedia = true, durationMs = 200_000, positionMs = 10_000, sampledAt = 1_000, isPlaying = true)

    @Test
    fun `position keeps counting during playback`() {
        assertEquals(15_000, playing.positionAt(now = 6_000))
    }

    @Test
    fun `position stands still while paused`() {
        assertEquals(10_000, playing.copy(isPlaying = false).positionAt(now = 60_000))
    }

    @Test
    fun `position does not go past the end`() {
        assertEquals(200_000, playing.positionAt(now = 10_000_000))
    }

    @Test
    fun `differing clocks do not yield a negative time span`() {
        assertEquals(10_000, playing.positionAt(now = 0))
    }

    @Test
    fun `position keeps counting at the speed of the book`() {
        val book = playing.copy(kind = WearRemoteMediaKind.BOOK, bookId = 3, speed = 1.5f)
        assertEquals(17_500, book.positionAt(now = 6_000))
    }

    @Test
    fun `sleep timer with a fixed duration always counts down`() {
        val timer = playing.copy(sleepTimerRemainingMs = 600_000)
        assertEquals(595_000L, timer.sleepTimerRemainingAt(now = 6_000))
        assertEquals(595_000L, timer.copy(isPlaying = false).sleepTimerRemainingAt(now = 6_000))
        assertEquals(0L, timer.sleepTimerRemainingAt(now = 10_000_000))
        assertNull(playing.sleepTimerRemainingAt(now = 6_000))
    }

    @Test
    fun `sleep timer until the end stands still while paused`() {
        val toEnd = playing.copy(sleepTimerRemainingMs = 600_000, sleepTimerToEnd = true)
        assertEquals(595_000L, toEnd.sleepTimerRemainingAt(now = 6_000))
        assertEquals(600_000L, toEnd.copy(isPlaying = false).sleepTimerRemainingAt(now = 60_000))
    }

    @Test
    fun `sleep timer is only reported again on start, end and change`() {
        val none = playing
        val running = playing.copy(sleepTimerRemainingMs = 600_000)
        assertEquals(false, none.sleepTimerDiffers(now = 6_000, remainingMs = null, toEnd = false))
        assertEquals(true, none.sleepTimerDiffers(now = 6_000, remainingMs = 900_000, toEnd = false))
        assertEquals(true, running.sleepTimerDiffers(now = 6_000, remainingMs = null, toEnd = false))
        // The timer's per-second tick does not count, a restart with a different duration does.
        assertEquals(false, running.sleepTimerDiffers(now = 6_000, remainingMs = 594_000, toEnd = false))
        assertEquals(true, running.sleepTimerDiffers(now = 6_000, remainingMs = 1_800_000, toEnd = false))
        assertEquals(true, running.sleepTimerDiffers(now = 6_000, remainingMs = 595_000, toEnd = true))
    }

    @Test
    fun `older phones report no sleep timer`() {
        val decoded = WearCodec.decode<WearRemoteStateDto>("""{"hasMedia":true,"title":"Moonlight"}""".encodeToByteArray())
        assertEquals(false, decoded.sleepTimerSupported)
        assertNull(decoded.sleepTimerRemainingMs)
    }

    @Test
    fun `cover key separates albums and books`() {
        assertEquals(12L, WearRemoteStateDto(hasMedia = true, albumId = 12).coverKey)
        assertEquals(-12L, WearRemoteStateDto(hasMedia = true, kind = WearRemoteMediaKind.EPISODE, bookId = 12).coverKey)
        assertNull(WearRemoteStateDto(hasMedia = true, kind = WearRemoteMediaKind.BOOK).coverKey)
    }

    @Test
    fun `older states without kind count as music`() {
        val decoded = WearCodec.decode<WearRemoteStateDto>("""{"hasMedia":true,"title":"Moonlight","albumId":4}""".encodeToByteArray())
        assertEquals(WearRemoteMediaKind.MUSIC, decoded.kind)
        assertEquals(1f, decoded.speed)
        assertEquals(4L, decoded.coverKey)
    }

    @Test
    fun `track list survives gzip`() {
        val result = WearRemoteTracksResult("r1", title = "Album", tracks = (1L..300).map { WearRemoteTrackDto(it, "Track $it", "Artist") })
        val decoded = WearCodec.decodeCompressed<WearRemoteTracksResult>(WearCodec.encodeCompressed(result))
        assertEquals(result, decoded)
    }

    @Test
    fun `cover keeps album and bytes`() {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val (albumId, bytes) = WearCodec.decodeCover(WearCodec.encodeCover(42, jpeg))!!
        assertEquals(42, albumId)
        assertArrayEquals(jpeg, bytes)
    }

    @Test
    fun `cover without bytes is empty but valid`() {
        assertEquals(0, WearCodec.decodeCover(WearCodec.encodeCover(7, ByteArray(0)))!!.second.size)
        assertNull(WearCodec.decodeCover(ByteArray(3)))
    }
}
