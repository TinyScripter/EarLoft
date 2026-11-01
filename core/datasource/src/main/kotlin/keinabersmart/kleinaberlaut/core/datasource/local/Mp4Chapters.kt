package keinabersmart.kleinaberlaut.core.datasource.local

/** Lesezugriff auf eine Datei an beliebiger Stelle; ein m4b ist zu groß, um ihn ganz zu laden. */
internal interface RandomAccessSource {
    val length: Long

    /** Höchstens [length] Bytes ab [position]; weniger am Dateiende. */
    fun read(position: Long, length: Int): ByteArray
}

/** Ein Kapitel einer MP4-Datei: Beginn in der Datei und Titel. Das Ende ergibt sich aus dem nächsten Beginn. */
internal data class Mp4ChapterMark(val startMs: Long, val title: String)

/**
 * Liest die Kapitel eines m4b/m4a aus den MP4-Atomen (Konzept 12.1, Spike „m4b-Kapitel“). Zwei Verfahren sind
 * verbreitet: die Liste `moov/udta/chpl` (Nero, ffmpeg, mp4v2) und eine Textspur, auf die `tref/chap` zeigt
 * (QuickTime/Apple, Audible). Gibt es beides, gilt `chpl`. Defekte Dateien liefern eine leere Liste.
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
        // Abgeschnittene oder verdrehte Atome (Index außerhalb, Größe 0): dann eben ohne Kapitel.
        emptyList()
    }

    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
    private const val MAX_CHAPTERS = 10_000

    private class Box(val type: String, val start: Int, val end: Int)

    /** Das `moov`-Atom ohne Kopf; die Mediendaten davor oder dahinter werden übersprungen. */
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
        p += 4 // Version + Flags
        if (version == 1) p += 4 // reserviert
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

        // Größe je Sample.
        val fixedSize = moov.u32(stsz.start + 4)
        val sampleCount = minOf(moov.u32(stsz.start + 8), MAX_CHAPTERS.toLong()).toInt()
        val sizes = LongArray(sampleCount) { if (fixedSize != 0L) fixedSize else moov.u32(stsz.start + 12 + it * 4) }

        // Beginn je Sample in Zeiteinheiten (stts: Anzahl, Dauer).
        val starts = LongArray(sampleCount)
        var sample = 0
        var time = 0L
        for (entry in 0 until moov.u32(stts.start + 4).toInt()) {
            val count = moov.u32(stts.start + 8 + entry * 8)
            val delta = moov.u32(stts.start + 12 + entry * 8)
            var n = 0L
            while (n < count && sample < sampleCount) {
                starts[sample++] = time
                time += delta
                n++
            }
        }

        // Stelle je Sample: Chunk-Anfang aus stco/co64 plus die Größen der Samples davor im selben Chunk.
        val chunkCount = moov.u32(chunkOffsets.start + 4).toInt()
        val runs = (0 until moov.u32(stsc.start + 4).toInt()).map { entry ->
            moov.u32(stsc.start + 8 + entry * 12) to moov.u32(stsc.start + 12 + entry * 12)
        }
        val marks = mutableListOf<Mp4ChapterMark>()
        var next = 0
        for (chunk in 1..chunkCount) {
            if (next >= sampleCount) break
            val perChunk = runs.lastOrNull { it.first <= chunk }?.second?.toInt() ?: break
            var offset = if (wide) moov.u64(chunkOffsets.start + 8 + (chunk - 1) * 8) else moov.u32(chunkOffsets.start + 8 + (chunk - 1) * 4)
            repeat(perChunk) {
                if (next >= sampleCount) return@repeat
                val size = sizes[next].toInt()
                marks += Mp4ChapterMark(starts[next] * 1000 / timescale, sampleText(source.read(offset, size)))
                offset += size
                next++
            }
        }
        return marks
    }

    private fun trackId(moov: Buffer, trak: Box): Long? {
        val tkhd = moov.find(trak, "tkhd") ?: return null
        return moov.u32(tkhd.start + if (moov.u8(tkhd.start) == 1) 20 else 12)
    }

    /** Textsample: 16-Bit-Länge, dann der Text (UTF-8, oder UTF-16 mit Bytereihenfolge-Marke). */
    private fun sampleText(bytes: ByteArray): String {
        if (bytes.size < 2) return ""
        val length = minOf(((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF), bytes.size - 2)
        val utf16 = length >= 2 && bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
        return String(bytes, 2, length, if (utf16) Charsets.UTF_16 else Charsets.UTF_8).trim('\u0000', ' ')
    }

    /** Bytes eines `moov`-Atoms samt Hilfen zum Lesen und zum Durchlaufen der Atombäume. */
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

        /** Folgt dem Pfad aus Atomtypen von [from] aus; `null`, wenn ein Glied fehlt. */
        fun find(from: Box, vararg path: String): Box? {
            var box = from
            for (type in path) box = children(box).firstOrNull { it.type == type } ?: return null
            return box
        }
    }
}
