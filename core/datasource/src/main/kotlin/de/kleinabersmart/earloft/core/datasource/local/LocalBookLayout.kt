package de.kleinabersmart.earloft.core.datasource.local

import java.math.BigInteger

/** An audio file in the "Audiobooks" folder, as raw as MediaStore delivers it. */
internal data class AudioFileRow(
    val id: Long,
    val path: String,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val durationMs: Long,
    /** MediaStore encodes disc and track as `disc * 1000 + track`; `0` = not specified. */
    val track: Int,
    val mimeType: String?,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    /** Storage volume (Android 10 and later), see [VolumeWatch]. */
    val volume: String? = null,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** The files of a book in playback order. [key] is stable (path from "Audiobooks/") and serves as the ID. */
internal data class LocalBookFiles(val key: String, val folderName: String?, val files: List<AudioFileRow>)

/**
 * Which files form a book (concept 12.1): a folder in "Audiobooks" is a book with one file per chapter
 * (MP3 folder); a file directly in "Audiobooks" is a book of its own (m4b). Subfolders form separate books.
 */
internal object LocalBookLayout {
    const val ROOT = "Audiobooks"

    private const val UNKNOWN = "<unknown>"

    /** Path from the folder [rootName] (also on SD cards); `null` if the file isn't inside it. */
    fun relativePath(path: String, rootName: String = ROOT): String? {
        val segments = path.split('/')
        val root = segments.indexOfFirst { it.equals(rootName, ignoreCase = true) }
        return if (root >= 0 && root < segments.lastIndex) segments.drop(root).joinToString("/") else null
    }

    fun group(rows: List<AudioFileRow>): List<LocalBookFiles> {
        val inRoot = mutableListOf<Pair<String, AudioFileRow>>()
        val folders = linkedMapOf<String, MutableList<AudioFileRow>>()
        for (row in rows) {
            val relative = relativePath(row.path) ?: continue
            val folder = relative.substringBeforeLast('/')
            if (folder.equals(ROOT, ignoreCase = true)) inRoot += relative to row else folders.getOrPut(folder) { mutableListOf() } += row
        }
        val books = inRoot.map { (relative, row) -> LocalBookFiles(relative, null, listOf(row)) } +
            folders.map { (folder, files) -> LocalBookFiles("$folder/", folder.substringAfterLast('/'), sortInReadingOrder(files)) }
        return books.sortedBy { it.key.lowercase() }
    }

    /**
     * Order of a folder's files: 1. track numbers, if all are present and distinct; 2. count in the title
     * ("04/30", "3 von 12") – file names like `12209255-audiothek.mp3` say nothing; 3. file name, numbers as numbers.
     */
    fun sortInReadingOrder(files: List<AudioFileRow>): List<AudioFileRow> {
        if (files.size < 2) return files
        val tracks = files.map { it.track }
        if (tracks.all { it > 0 } && tracks.toSet().size == tracks.size) return files.sortedBy { it.track }
        val ordinals = files.map { titleOrdinal(it.title) }
        if (ordinals.all { it != null } && ordinals.toSet().size == ordinals.size) return files.sortedBy { titleOrdinal(it.title) }
        return files.sortedWith { a, b -> naturalCompare(a.fileName, b.fileName) }
    }

    private val ORDINAL = Regex("""(?<!\d)(\d{1,3})\s*(?:/|von|of)\s*(\d{1,3})(?!\d)""")

    /** "(04/30)" → 4; a date like "25/12" doesn't count (the counter must not exceed the total). */
    fun titleOrdinal(title: String?): Int? {
        if (title == null) return null
        return ORDINAL.findAll(title).firstNotNullOfOrNull { match ->
            val (number, total) = match.destructured
            number.toInt().takeIf { it in 1..total.toInt() }
        }
    }

    private val CHUNKS = Regex("""\d+|\D+""")

    /** Compares names so that "Kapitel 2" comes before "Kapitel 10". */
    fun naturalCompare(a: String, b: String): Int {
        val left = CHUNKS.findAll(a).map { it.value }.toList()
        val right = CHUNKS.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0].isDigit() && y[0].isDigit()) BigInteger(x).compareTo(BigInteger(y)) else x.compareTo(y, ignoreCase = true)
            if (result != 0) return result
        }
        return left.size.compareTo(right.size)
    }

    /** Chapter name from the title tag; details after " | " (book, date) are dropped, without a tag the file name applies. */
    fun chapterTitle(rawTitle: String?, fileName: String): String {
        val title = rawTitle?.substringBefore(" | ")?.trim().orEmpty()
        return title.ifEmpty { fileName.substringBeforeLast('.').replace('_', ' ').trim() }
    }

    /** The most frequent non-empty value (on a tie the first one). */
    fun commonValue(values: List<String?>): String? =
        values.mapNotNull { it?.trim()?.takeIf { v -> v.isNotEmpty() && v != UNKNOWN } }
            .groupingBy { it }.eachCount().entries
            .maxByOrNull { it.value }?.key

    /**
     * Book title: for one file its title, for several the common album, otherwise [folderTitle] (the folder name without volume,
     * year and narrator, see [FolderMetadata]).
     */
    fun bookTitle(book: LocalBookFiles, folderTitle: String? = book.folderName): String {
        // Without tags, MediaStore reports the file name as title and the folder as album: that says nothing about the book.
        val parent = book.files.first().path.substringBeforeLast('/').substringAfterLast('/')
        val album = commonValue(book.files.map { it.album })?.takeIf { it != parent }
        val single = book.files.singleOrNull()
        val title = if (single != null) {
            val baseName = single.fileName.substringBeforeLast('.')
            commonValue(listOf(single.title))?.takeIf { it != baseName } ?: album ?: folderTitle ?: baseName
        } else {
            album ?: folderTitle ?: book.files.first().fileName
        }
        return title.replace('_', ' ').trim()
    }

    /**
     * 1: ID3 chapters of single MP3s, subtitle and year from the tags (stage 4). 2: folder names, companion files, series and
     * description from the tags (`books_improvements.md` M7). 3: volumes with letters in the folder name ("2a – Titel").
     */
    private const val READ_VERSION = 3

    fun bookAuthor(book: LocalBookFiles): String =
        commonValue(book.files.map { it.albumArtist }) ?: commonValue(book.files.map { it.artist }).orEmpty()

    /**
     * Changes as soon as a file is added, removed or changed – and with [READ_VERSION] when reading
     * yields more than before (then every book is reread once).
     */
    fun fingerprint(book: LocalBookFiles): Long {
        var hash = 1125899906842597L + READ_VERSION
        for (file in book.files) {
            hash = 31 * hash + "${file.id}|${file.sizeBytes}|${file.dateModifiedSec}".hashCode()
        }
        return hash
    }
}
