package de.kleinabersmart.earloft.core.datasource.local

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Read access to a file at any position; an m4b is too large to load it entirely. */
internal interface RandomAccessSource {
    val length: Long

    /** At most [length] bytes from [position]; fewer at the end of the file. */
    fun read(position: Long, length: Int): ByteArray
}

/** A chapter of an MP4 file: start in the file and title. The end follows from the next start. */
internal data class Mp4ChapterMark(val startMs: Long, val title: String)

/** Chapters from [marks] of a file of [durationMs], shifted by [offsetMs] (start of the file in the book). */
internal fun List<Mp4ChapterMark>.toChapters(durationMs: Long, offsetMs: Long = 0): List<LocalBookChapter> =
    mapIndexed { index, mark ->
        val end = getOrNull(index + 1)?.startMs ?: durationMs
        LocalBookChapter(mark.title.ifBlank { "${index + 1}" }, offsetMs + mark.startMs, offsetMs + end.coerceAtLeast(mark.startMs))
    }

/** m4b, m4a and mp4 by type or extension. */
internal fun isMp4(mimeType: String?, fileName: String): Boolean =
    mimeType?.contains("mp4", ignoreCase = true) == true || fileName.substringAfterLast('.').lowercase() in setOf("m4b", "m4a", "mp4")

/** [RandomAccessSource] over a [FileChannel]. */
internal class ChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val length: Long get() = channel.size()

    override fun read(position: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) break
            at += read
        }
        return buffer.array().copyOf(buffer.position())
    }
}

/**
 * Reads the chapters of an m4b/m4a from the MP4 atoms (concept 12.1, spike "m4b chapters"). Two methods are
 * common: the list `moov/udta/chpl` (Nero, ffmpeg, mp4v2) and a text track that `tref/chap` points to
 * (QuickTime/Apple, also in audiobook stores). If both exist, `chpl` wins. Broken files return an empty list.
 */
internal object Mp4Chapters {

    fun read(source: RandomAccessSource): List<Mp4ChapterMark> = try {
        val moov = readMoov(source)
        if (moov == null) {
            emptyList()
        } else {
            readChpl(moov).ifEmpty { readTextTrack(moov, source) }
        }
    } catch (_: RuntimeException) {
        // Truncated or garbled atoms (index out of range, size 0): then simply without chapters.
        emptyList()
    } catch (_: OutOfMemoryError) {
        // Safety net: a large moov on a device with little heap. The sizes below are capped, so this is rare.
        emptyList()
    }

    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private const val MAX_CHAPTERS = 10_000

    /** A text sample is a 16-bit length plus the text; anything behind it (e.g. an `encd` atom) is not needed. */
    private const val MAX_SAMPLE_BYTES = 2 + 0xFFFF

    private class Box(val type: String, val start: Int, val end: Int)

    /** The `moov` atom without header; the media data before or after it is skipped. */
    private fun readMoov(source: RandomAccessSource): Buffer? {
        var position = 0L
        while (position + 8 <= source.length) {
            val head = source.read(position, 16)
            if (head.size < 8) return null
            val buffer = Buffer(head)
            var size = buffer.u32(0)
            val type = buffer.type(4)
            var headerSize = 8
            if (size == 1L) {
                if (head.size < 16) return null
                size = buffer.u64(8)
                headerSize = 16
            } else if (size == 0L) {
                size = source.length - position
            }
            if (size < headerSize) return null
            if (type == "moov") {
                val payload = size - headerSize
                if (payload > MAX_MOOV_BYTES || position + size > source.length) return null
                return Buffer(source.read(position + headerSize, payload.toInt()))
            }
            position += size
        }
        return null
    }

    private fun readChpl(moov: Buffer): List<Mp4ChapterMark> {
        val chpl = moov.find(moov.root(), "udta", "chpl") ?: return emptyList()
        var p = chpl.start
        val version = moov.u8(p)
        p += 4 // version + flags
        if (version == 1) p += 4 // reserved
        val count = moov.u8(p++)
        val marks = mutableListOf<Mp4ChapterMark>()
        repeat(count) {
            if (p + 9 > chpl.end) return@repeat
            val start100ns = moov.u64(p)
            val length = moov.u8(p + 8)
            p += 9
            val title = moov.text(p, minOf(length, chpl.end - p))
            p += length
            marks += Mp4ChapterMark(start100ns / 10_000, title)
        }
        return marks
    }

    private fun readTextTrack(moov: Buffer, source: RandomAccessSource): List<Mp4ChapterMark> {
        val tracks = moov.children(moov.root()).filter { it.type == "trak" }
        val referenced = tracks.flatMap { trak ->
            moov.find(trak, "tref", "chap")?.let { chap -> (chap.start until chap.end - 3 step 4).map { moov.u32(it) } }.orEmpty()
        }.toSet()
        if (referenced.isEmpty()) return emptyList()
        val chapterTrack = tracks.firstOrNull { trak -> trackId(moov, trak) in referenced } ?: return emptyList()

        val timescale = moov.find(chapterTrack, "mdia", "mdhd")?.let { mdhd ->
            moov.u32(mdhd.start + if (moov.u8(mdhd.start) == 1) 20 else 12)
        }?.takeIf { it > 0 } ?: return emptyList()
        val stbl = moov.find(chapterTrack, "mdia", "minf", "stbl") ?: return emptyList()
        val stts = moov.find(stbl, "stts") ?: return emptyList()
        val stsz = moov.find(stbl, "stsz") ?: return emptyList()
        val stsc = moov.find(stbl, "stsc") ?: return emptyList()
        val chunkOffsets = (moov.find(stbl, "stco") ?: moov.find(stbl, "co64")) ?: return emptyList()
        val wide = chunkOffsets.type == "co64"

        // Size per sample. Every count is capped by what its atom can hold, so garbled counts cannot run long loops.
        val fixedSize = moov.u32(stsz.start + 4)
        val sizeEntries = if (fixedSize != 0L) Long.MAX_VALUE else entries(stsz, 12, 4)
        val sampleCount = minOf(moov.u32(stsz.start + 8), MAX_CHAPTERS.toLong(), sizeEntries).toInt()
        val sizes = LongArray(sampleCount) { if (fixedSize != 0L) fixedSize else moov.u32(stsz.start + 12 + it * 4) }

        // Start per sample in time units (stts: count, duration).
        val starts = LongArray(sampleCount)
        var sample = 0
        var time = 0L
        for (entry in 0 until minOf(moov.u32(stts.start + 4), entries(stts, 8, 8)).toInt()) {
            if (sample >= sampleCount) break
            val count = moov.u32(stts.start + 8 + entry * 8)
            val delta = moov.u32(stts.start + 12 + entry * 8)
            var n = 0L
            while (n < count && sample < sampleCount) {
                starts[sample++] = time
                time += delta
                n++
            }
        }

        // Position per sample: chunk start from stco/co64 plus the sizes of the preceding samples in the same chunk.
        val chunkCount = minOf(moov.u32(chunkOffsets.start + 4), entries(chunkOffsets, 8, if (wide) 8 else 4)).toInt()
        val runs = (0 until minOf(moov.u32(stsc.start + 4), entries(stsc, 8, 12)).toInt()).map { entry ->
            moov.u32(stsc.start + 8 + entry * 12) to moov.u32(stsc.start + 12 + entry * 12)
        }
        val marks = mutableListOf<Mp4ChapterMark>()
        var next = 0
        var run = -1
        for (chunk in 1..chunkCount) {
            if (next >= sampleCount) break
            // Runs are sorted by their first chunk; walk them along instead of searching per chunk.
            while (run + 1 < runs.size && runs[run + 1].first <= chunk) run++
            if (run < 0) break
            val perChunk = runs[run].second
            var offset = if (wide) moov.u64(chunkOffsets.start + 8 + (chunk - 1) * 8) else moov.u32(chunkOffsets.start + 8 + (chunk - 1) * 4)
            var inChunk = 0L
            while (inChunk < perChunk && next < sampleCount) {
                val size = sizes[next]
                // Only the text is read, never more than the file still holds behind the offset.
                val length = minOf(size, MAX_SAMPLE_BYTES.toLong(), (source.length - offset).coerceAtLeast(0)).toInt()
                marks += Mp4ChapterMark(starts[next] * 1000 / timescale, sampleText(source.read(offset, length)))
                offset += size
                next++
                inChunk++
            }
        }
        return marks
    }

    /** How many entries of [entrySize] bytes fit into [box] behind a header of [headerSize] bytes. */
    private fun entries(box: Box, headerSize: Int, entrySize: Int): Long =
        ((box.end - box.start - headerSize) / entrySize).coerceAtLeast(0).toLong()

    private fun trackId(moov: Buffer, trak: Box): Long? {
        val tkhd = moov.find(trak, "tkhd") ?: return null
        return moov.u32(tkhd.start + if (moov.u8(tkhd.start) == 1) 20 else 12)
    }

    /** Text sample: 16-bit length, then the text (UTF-8, or UTF-16 with byte order mark). */
    private fun sampleText(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val length = minOf(((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF), bytes.size - 2)
        val utf16 = length >= 2 && bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
        return String(bytes, 2, length, if (utf16) Charsets.UTF_16 else Charsets.UTF_8).trim('\u0000', ' ')
    }

    /** Bytes of a `moov` atom with helpers for reading and traversing the atom trees. */
    private class Buffer(private val data: ByteArray) {
        fun root() = Box("moov", 0, data.size)

        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u32(i: Int): Long =
            (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
        fun u64(i: Int): Long = (u32(i) shl 32) or u32(i + 4)
        fun type(i: Int) = String(data, i, 4, Charsets.ISO_8859_1)
        fun text(i: Int, length: Int) = String(data, i, length, Charsets.UTF_8)

        fun children(parent: Box): List<Box> {
            val list = mutableListOf<Box>()
            var p = parent.start
            while (p + 8 <= parent.end) {
                var size = u32(p)
                var header = 8
                if (size == 1L) {
                    size = u64(p + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (parent.end - p).toLong()
                }
                if (size < header || p + size > parent.end) break
                list += Box(type(p + 4), p + header, (p + size).toInt())
                p += size.toInt()
            }
            return list
        }

        /** Follows the path of atom types starting from [from]; `null` if a link is missing. */
        fun find(from: Box, vararg path: String): Box? {
            var box = from
            for (type in path) box = children(box).firstOrNull { it.type == type } ?: return null
            return box
        }
    }
}
