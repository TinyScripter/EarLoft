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

/**
 * The files of a book in playback order. [key] is stable (path from "Audiobooks/") and serves as the ID. [folderPath] is the
 * book's folder on the device (with disc subfolders the one above them, where cover and companion files lie); `null` for a
 * file directly in "Audiobooks".
 */
internal data class LocalBookFiles(
    val key: String,
    val folderName: String?,
    val files: List<AudioFileRow>,
    val folderPath: String? = null,
)

/**
 * Which files form a book (concept 12.1): a folder in "Audiobooks" is a book with one file per chapter
 * (MP3 folder); a file directly in "Audiobooks" is a book of its own (m4b). Subfolders form separate books, except disc
 * folders ("CD 1", "Disc 2", [discNumber]): like with Audiobookshelf they belong to the book above them, unless their album tags
 * name different books (the parts of a series).
 */
internal object LocalBookLayout {
    const val ROOT = "Audiobooks"

    private const val UNKNOWN = "<unknown>"

    /** MediaStore's name of the device's own storage. */
    private const val PRIMARY_VOLUME = "external_primary"

    /** Path from the folder [rootName] (also on SD cards); `null` if the file isn't inside it. */
    fun relativePath(path: String, rootName: String = ROOT): String? {
        val segments = path.split('/')
        val root = segments.indexOfFirst { it.equals(rootName, ignoreCase = true) }
        return if (root >= 0 && root < segments.lastIndex) segments.drop(root).joinToString("/") else null
    }

    fun group(rows: List<AudioFileRow>): List<LocalBookFiles> {
        val inRoot = mutableListOf<Pair<String, AudioFileRow>>()
        val folders = linkedMapOf<String, MutableList<FileOfBook>>()
        for (row in rows) {
            val relative = relativePath(row.path) ?: continue
            val folder = relative.substringBeforeLast('/')
            if (folder.equals(ROOT, ignoreCase = true)) {
                inRoot += relative to row
                continue
            }
            // A disc folder directly in "Audiobooks" has no book above it and stays a book of its own.
            val parent = folder.substringBeforeLast('/')
            val disc = discNumber(folder.substringAfterLast('/'))?.takeUnless { parent.equals(ROOT, ignoreCase = true) }
            if (disc != null) {
                folders.getOrPut(parent) { mutableListOf() } +=
                    FileOfBook(row, disc, row.path.substringBeforeLast('/').substringBeforeLast('/'), discFolder = folder)
            } else {
                folders.getOrPut(folder) { mutableListOf() } += FileOfBook(row, 0, row.path.substringBeforeLast('/'))
            }
        }
        for ((folder, entries) in folders.entries.toList()) {
            val apart = separateBooks(entries) ?: continue
            folders[folder] = entries.filterTo(mutableListOf()) { it.discFolder == null }
            for ((discFolder, discEntries) in apart) {
                folders[discFolder] = discEntries.mapTo(mutableListOf()) { FileOfBook(it.row, 0, it.row.path.substringBeforeLast('/')) }
            }
            if (folders.getValue(folder).isEmpty()) folders.remove(folder)
        }
        // The same path on two volumes (internal and SD card, e.g. while moving) is one book from one of them: otherwise its chapters
        // came twice, or two books with the same key swapped places at every scan (review5 P36).
        val books = inRoot.groupBy({ it.first }, { it.second }).map { (relative, rows) -> LocalBookFiles(relative, null, oneVolume(rows)) } +
            folders.map { (folder, entries) ->
                val discOf = entries.associate { it.row.id to it.disc }
                val files = oneVolume(entries.map { it.row })
                // Disc by disc, each in its own reading order (track numbers usually start at 1 on every disc).
                val ordered = files.groupBy { discOf.getValue(it.id) }.toSortedMap().values.flatMap(::sortInReadingOrder)
                val folderPath = entries.first { it.row.id == ordered.first().id }.bookFolderPath
                LocalBookFiles("$folder/", folder.substringAfterLast('/'), ordered, folderPath)
            }
        return books.sortedBy { it.key.lowercase() }
    }

    private class FileOfBook(val row: AudioFileRow, val disc: Int, val bookFolderPath: String, val discFolder: String? = null)

    /**
     * The disc folders of one parent by folder, if their album tags name different books ("Teil 1", "Teil 2" of a series, each a
     * novel of its own); `null` if they are discs of one book: same album without the disc suffix, or no tags at all.
     */
    private fun separateBooks(entries: List<FileOfBook>): Map<String, List<FileOfBook>>? {
        val byDisc = entries.filter { it.discFolder != null }.groupBy { it.discFolder!! }
        if (byDisc.size < 2) return null
        val albums = byDisc.values.mapNotNull { files ->
            val parent = files.first().row.path.substringBeforeLast('/').substringAfterLast('/')
            // Without tags MediaStore reports the folder as album, which says nothing about the book.
            commonValue(files.map { it.row.album?.takeIf { album -> album != parent }?.replace(DISC_SUFFIX, "") })
        }
        return byDisc.takeIf { albums.distinctBy { it.lowercase() }.size > 1 }
    }

    private val DISC_SUFFIX = Regex("""\s*[-–:,(\[]?\s*(?:cd|dis[ck]|teil)\s*\d{1,3}\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)

    private val DISC_FOLDER = Regex("""^(?:cd|dis[ck]|teil)\s*[-_.]?\s*(\d{1,3})$""", RegexOption.IGNORE_CASE)

    /**
     * The number of a disc folder ("CD 1", "cd02", "Disc 3", "Disk_4", "Teil 2"), `null` for any other folder name: a name that is
     * only that says nothing about a book, ripped audiobooks often come so (review5 P35).
     */
    fun discNumber(folderName: String): Int? = DISC_FOLDER.matchEntire(folderName.trim())?.groupValues?.get(1)?.toInt()

    /** The files of the volume holding most of [files]; on a tie the device's own storage, then by name. */
    private fun oneVolume(files: List<AudioFileRow>): List<AudioFileRow> {
        val byVolume = files.groupBy { it.volume }
        if (byVolume.size < 2) return files
        return byVolume.entries.sortedWith(
            compareByDescending<Map.Entry<String?, List<AudioFileRow>>> { it.value.size }
                .thenByDescending { it.key == PRIMARY_VOLUME }
                .thenBy { it.key.orEmpty() },
        ).first().value
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
        // Ripped discs often name the album per disc ("Title CD 1", "Title (Disc 2)"): one title for the whole book.
        val fromDiscs = book.files.map { it.path.substringBeforeLast('/') }.distinct().size > 1
        val albums = book.files.map { file -> file.album?.let { if (fromDiscs) it.replace(DISC_SUFFIX, "") else it } }
        val album = commonValue(albums)?.takeIf { it != parent }
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
