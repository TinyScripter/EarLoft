package keinabersmart.kleinaberlaut.core.datasource.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Mp4ChaptersTest {

    @Test
    fun `reads Nero chapter list`() {
        val chpl = fullBox("chpl", 1, payload = u32(0) + byteArrayOf(2) + chplEntry(0, "Vorspann") + chplEntry(15_788, "Kapitel 1"))
        val file = ftyp() + box("moov", box("udta", chpl))

        assertEquals(
            listOf(Mp4ChapterMark(0, "Vorspann"), Mp4ChapterMark(15_788, "Kapitel 1")),
            Mp4Chapters.read(bytes(file)),
        )
    }

    @Test
    fun `reads QuickTime chapter text track behind mdat`() {
        val samples = listOf("Vorspann", "Wurststullen auf Bali", "Abspann").map { sample(it) }
        val mdat = box("mdat", samples.fold(ByteArray(0)) { acc, s -> acc + s })
        val prefix = ftyp()
        // Samples liegen hinter dem mdat-Kopf; ein Chunk mit drei Samples.
        val chunkOffset = (prefix.size + 8).toLong()
        val textTrack = trak(
            id = 2,
            tref = null,
            timescale = 1000,
            stbl = box(
                "stbl",
                fullBox("stts", 0, u32(1) + u32(3) + u32(15_788)) + // drei Samples à 15 788 ticks
                    fullBox("stsc", 0, u32(1) + u32(1) + u32(3) + u32(1)) +
                    fullBox("stsz", 0, u32(0) + u32(3) + samples.fold(ByteArray(0)) { acc, s -> acc + u32(s.size.toLong()) }) +
                    fullBox("stco", 0, u32(1) + u32(chunkOffset)),
            ),
        )
        val audioTrack = trak(id = 1, tref = box("tref", box("chap", u32(2))), timescale = 44100, stbl = null)

        val marks = Mp4Chapters.read(bytes(prefix + mdat + box("moov", audioTrack + textTrack)))

        assertEquals(listOf("Vorspann", "Wurststullen auf Bali", "Abspann"), marks.map { it.title })
        assertEquals(listOf(0L, 15_788L, 31_576L), marks.map { it.startMs })
    }

    @Test
    fun `a file without chapters or garbage gives an empty list`() {
        assertTrue(Mp4Chapters.read(bytes(ftyp() + box("moov", box("udta", ByteArray(0)))))
            .isEmpty())
        assertTrue(Mp4Chapters.read(bytes(ByteArray(40) { 0x7F })).isEmpty())
        assertTrue(Mp4Chapters.read(bytes(ByteArray(0))).isEmpty())
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

    /** Atom mit Version und Flags vor den Daten. */
    private fun fullBox(type: String, version: Int, payload: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + payload)

    private fun bytes(data: ByteArray) = object : RandomAccessSource {
        override val length: Long = data.size.toLong()
        override fun read(position: Long, length: Int): ByteArray {
            val from = position.toInt().coerceAtMost(data.size)
            return data.copyOfRange(from, (from + length).coerceAtMost(data.size))
        }
    }
}
