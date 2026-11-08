package de.kleinabersmart.earloft.core.wearsync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRemoteTest {

    private val playing = WearRemoteStateDto(hasMedia = true, durationMs = 200_000, positionMs = 10_000, sampledAt = 1_000, isPlaying = true)

    @Test
    fun `Position zaehlt waehrend der Wiedergabe weiter`() {
        assertEquals(15_000, playing.positionAt(now = 6_000))
    }

    @Test
    fun `Position steht bei Pause`() {
        assertEquals(10_000, playing.copy(isPlaying = false).positionAt(now = 60_000))
    }

    @Test
    fun `Position geht nicht ueber das Ende hinaus`() {
        assertEquals(200_000, playing.positionAt(now = 10_000_000))
    }

    @Test
    fun `Abweichende Uhrzeiten ergeben keine negative Zeitspanne`() {
        assertEquals(10_000, playing.positionAt(now = 0))
    }

    @Test
    fun `Position zaehlt im Tempo des Buchs weiter`() {
        val book = playing.copy(kind = WearRemoteMediaKind.BOOK, bookId = 3, speed = 1.5f)
        assertEquals(17_500, book.positionAt(now = 6_000))
    }

    @Test
    fun `Cover-Schluessel trennt Alben und Buecher`() {
        assertEquals(12L, WearRemoteStateDto(hasMedia = true, albumId = 12).coverKey)
        assertEquals(-12L, WearRemoteStateDto(hasMedia = true, kind = WearRemoteMediaKind.EPISODE, bookId = 12).coverKey)
        assertNull(WearRemoteStateDto(hasMedia = true, kind = WearRemoteMediaKind.BOOK).coverKey)
    }

    @Test
    fun `Aeltere Zustaende ohne Art gelten als Musik`() {
        val decoded = WearCodec.decode<WearRemoteStateDto>("""{"hasMedia":true,"title":"Mondlicht","albumId":4}""".encodeToByteArray())
        assertEquals(WearRemoteMediaKind.MUSIC, decoded.kind)
        assertEquals(1f, decoded.speed)
        assertEquals(4L, decoded.coverKey)
    }

    @Test
    fun `Titelliste uebersteht gzip`() {
        val result = WearRemoteTracksResult("r1", title = "Album", tracks = (1L..300).map { WearRemoteTrackDto(it, "Titel $it", "Künstler") })
        val decoded = WearCodec.decodeCompressed<WearRemoteTracksResult>(WearCodec.encodeCompressed(result))
        assertEquals(result, decoded)
    }

    @Test
    fun `Cover behaelt Album und Bytes`() {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val (albumId, bytes) = WearCodec.decodeCover(WearCodec.encodeCover(42, jpeg))!!
        assertEquals(42, albumId)
        assertArrayEquals(jpeg, bytes)
    }

    @Test
    fun `Cover ohne Bytes ist leer aber gueltig`() {
        assertEquals(0, WearCodec.decodeCover(WearCodec.encodeCover(7, ByteArray(0)))!!.second.size)
        assertNull(WearCodec.decodeCover(ByteArray(3)))
    }
}
