package de.kleinabersmart.earloft.core.datasource.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Mp4ChaptersTest {

    @Test
    fun `reads Nero chapter list`() {
        val chpl = fullBox("chpl", 1, payload = u32(0) + byteArrayOf(2) + chplEntry(0, "Intro") + chplEntry(15_788, "Chapter 1"))
        val file = ftyp() + box("moov", box("udta", chpl))

        assertEquals(
            listOf(Mp4ChapterMark(0, "Intro"), Mp4ChapterMark(15_788, "Chapter 1")),
            Mp4Chapters.read(bytes(file)),
        )
    }

    @Test
    fun `reads QuickTime chapter text track behind mdat`() {
        val samples = listOf("Intro", "Sandwiches in Bali", "Outro").map { sample(it) }
        val mdat = box("mdat", samples.fold(ByteArray(0)) { acc, s -> acc + s })
        val prefix = ftyp()
        // Samples are located after the mdat header; one chunk with three samples.
        val chunkOffset = (prefix.size + 8).toLong()
        val textTrack = trak(
            id = 2,
            tref = null,
            timescale = 1000,
            stbl = box(
                "stbl",
                fullBox("stts", 0, u32(1) + u32(3) + u32(15_788)) + // three samples of 15 788 ticks each
                    fullBox("stsc", 0, u32(1) + u32(1) + u32(3) + u32(1)) +
                    fullBox("stsz", 0, u32(0) + u32(3) + samples.fold(ByteArray(0)) { acc, s -> acc + u32(s.size.toLong()) }) +
                    fullBox("stco", 0, u32(1) + u32(chunkOffset)),
            ),
        )
        val audioTrack = trak(id = 1, tref = box("tref", box("chap", u32(2))), timescale = 44100, stbl = null)

        val marks = Mp4Chapters.read(bytes(prefix + mdat + box("moov", audioTrack + textTrack)))

        assertEquals(listOf("Intro", "Sandwiches in Bali", "Outro"), marks.map { it.title })
        assertEquals(listOf(0L, 15_788L, 31_576L), marks.map { it.startMs })
    }

    @Test
    fun `a file without chapters or garbage gives an empty list`() {
        assertTrue(Mp4Chapters.read(bytes(ftyp() + box("moov", box("udta", ByteArray(0)))))
            .isEmpty())
        assertTrue(Mp4Chapters.read(bytes(ByteArray(40) { 0x7F })).isEmpty())
        assertTrue(Mp4Chapters.read(bytes(ByteArray(0))).isEmpty())
    }

    @Test
    fun `a huge sample size only reads the text and not gigabytes`() {
        val samples = listOf(sample("Intro"), sample("Outro"))
        val mdat = box("mdat", samples[0] + samples[1])
        val prefix = ftyp()
        val chunkOffset = (prefix.size + 8).toLong()
        val textTrack = trak(
            id = 2,
            tref = null,
            timescale = 1000,
            stbl = box(
                "stbl",
                fullBox("stts", 0, u32(1) + u32(2) + u32(1000)) +
                    // Two chunks with one sample each; the first claims almost 2 GB.
                    fullBox("stsc", 0, u32(1) + u32(1) + u32(1) + u32(1)) +
                    fullBox("stsz", 0, u32(0) + u32(2) + u32(0x7FFF_FF00) + u32(samples[1].size.toLong())) +
                    fullBox("stco", 0, u32(2) + u32(chunkOffset) + u32(chunkOffset + samples[0].size)),
            ),
        )
        val audioTrack = trak(id = 1, tref = box("tref", box("chap", u32(2))), timescale = 44100, stbl = null)
        val source = bytes(prefix + mdat + box("moov", audioTrack + textTrack))

        val marks = Mp4Chapters.read(source)

        assertEquals(listOf("Intro", "Outro"), marks.map { it.title })
        assertTrue("largest read ${source.largestRead}", source.largestRead <= 2 + 0xFFFF)
    }

    @Test
    fun `garbled counts end quickly`() {
        val textTrack = trak(
            id = 2,
            tref = null,
            timescale = 1000,
            stbl = box(
                "stbl",
                fullBox("stts", 0, u32(0xFFFF_FFFF) + u32(1) + u32(1000)) +
                    // Zero samples per chunk and four billion chunks: nothing ever advances.
                    fullBox("stsc", 0, u32(0xFFFF_FFFF) + u32(1) + u32(0) + u32(1)) +
                    fullBox("stsz", 0, u32(10) + u32(0xFFFF_FFFF)) +
                    fullBox("stco", 0, u32(0xFFFF_FFFF) + u32(0)),
            ),
        )
        val audioTrack = trak(id = 1, tref = box("tref", box("chap", u32(2))), timescale = 44100, stbl = null)
        val started = System.nanoTime()

        val marks = Mp4Chapters.read(bytes(ftyp() + box("moov", audioTrack + textTrack)))

        assertTrue(marks.isEmpty())
        assertTrue(System.nanoTime() - started < 2_000_000_000L)
    }

    private fun trak(id: Long, tref: ByteArray?, timescale: Long, stbl: ByteArray?): ByteArray {
        val tkhd = fullBox("tkhd", 0, u32(0) + u32(0) + u32(id) + ByteArray(68))
        val mdhd = fullBox("mdhd", 0, u32(0) + u32(0) + u32(timescale) + u32(0) + ByteArray(4))
        val minf = box("minf", stbl ?: ByteArray(0))
        return box("trak", tkhd + (tref ?: ByteArray(0)) + box("mdia", mdhd + minf))
    }

    private fun sample(text: String): ByteArray {
        val utf8 = text.toByteArray()
        return byteArrayOf((utf8.size shr 8).toByte(), utf8.size.toByte()) + utf8
    }

    private fun chplEntry(startMs: Long, title: String): ByteArray {
        val utf8 = title.toByteArray()
        return ByteBuffer.allocate(8).putLong(startMs * 10_000).array() + byteArrayOf(utf8.size.toByte()) + utf8
    }

    private fun ftyp() = box("ftyp", "M4B ".toByteArray() + u32(0))

    private fun u32(value: Long) = ByteBuffer.allocate(4).putInt(value.toInt()).array()

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(u32(8L + payload.size))
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(payload)
        }.toByteArray()

    /** Atom with version and flags before the data. */
    private fun fullBox(type: String, version: Int, payload: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

    private fun bytes(data: ByteArray) = FakeSource(data)

    /** In-memory file that remembers the largest requested read. */
    private class FakeSource(private val data: ByteArray) : RandomAccessSource {
        var largestRead = 0
        override val length: Long = data.size.toLong()
        override fun read(position: Long, length: Int): ByteArray {
            largestRead = maxOf(largestRead, length)
            val from = position.toInt().coerceAtMost(data.size)
            return data.copyOfRange(from, (from + length).coerceAtMost(data.size))
        }
    }
}
