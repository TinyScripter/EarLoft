package de.kleinabersmart.earloft.core.datasource.local

import java.math.BigInteger

/** Eine Audiodatei im Ordner „Audiobooks“, so roh wie MediaStore sie liefert. */
internal data class AudioFileRow(
    val id: Long,
    val path: String,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val durationMs: Long,
    /** MediaStore kodiert Disc und Track als `disc * 1000 + track`; `0` = keine Angabe. */
    val track: Int,
    val mimeType: String?,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** Die Dateien eines Buchs in Abspielreihenfolge. [key] ist stabil (Pfad ab „Audiobooks/“) und dient als Kennung. */
internal data class LocalBookFiles(val key: String, val folderName: String?, val files: List<AudioFileRow>)

/**
 * Welche Dateien ein Buch bilden (Konzept 12.1): Ein Ordner in „Audiobooks“ ist ein Buch mit einer Datei je Kapitel
 * (MP3-Ordner); eine Datei direkt in „Audiobooks“ ist ein Buch für sich (m4b). Unterordner bilden eigene Bücher.
 */
internal object LocalBookLayout {
    const val ROOT = "Audiobooks"

    private const val UNKNOWN = "<unknown>"

    /** Pfad ab dem Ordner [rootName] (auch auf Speicherkarten); `null`, wenn die Datei nicht darin liegt. */
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
     * Reihenfolge der Dateien eines Ordners: 1. Tracknummern, wenn alle da und verschieden sind; 2. Zählung im Titel
     * („04/30“, „3 von 12“) – Dateinamen wie `12209255-audiothek.mp3` sagen nichts; 3. Dateiname, Zahlen als Zahlen.
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

    /** „(04/30)“ → 4; ein Datum wie „25/12“ zählt nicht (der Zähler darf nicht über der Gesamtzahl liegen). */
    fun titleOrdinal(title: String?): Int? {
        if (title == null) return null
        return ORDINAL.findAll(title).firstNotNullOfOrNull { match ->
            val (number, total) = match.destructured
            number.toInt().takeIf { it in 1..total.toInt() }
        }
    }

    private val CHUNKS = Regex("""\d+|\D+""")

    /** Vergleicht Namen so, dass „Kapitel 2“ vor „Kapitel 10“ steht. */
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

    /** Kapitelname aus dem Titel-Tag; Angaben nach „ | “ (Buch, Datum) fallen weg, ohne Tag gilt der Dateiname. */
    fun chapterTitle(rawTitle: String?, fileName: String): String {
        val title = rawTitle?.substringBefore(" | ")?.trim().orEmpty()
        return title.ifEmpty { fileName.substringBeforeLast('.').replace('_', ' ').trim() }
    }

    /** Der häufigste nicht leere Wert (bei Gleichstand der erste). */
    fun commonValue(values: List<String?>): String? =
        values.mapNotNull { it?.trim()?.takeIf { v -> v.isNotEmpty() && v != UNKNOWN } }
            .groupingBy { it }.eachCount().entries
            .maxByOrNull { it.value }?.key

    /**
     * Buchtitel: bei einer Datei ihr Titel, bei mehreren das gemeinsame Album, sonst [folderTitle] (der Ordnername ohne Band,
     * Jahr und Sprecher, siehe [FolderMetadata]).
     */
    fun bookTitle(book: LocalBookFiles, folderTitle: String? = book.folderName): String {
        // Ohne Tags nennt MediaStore den Dateinamen als Titel und den Ordner als Album: Das ist keine Angabe zum Buch.
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
     * 1: ID3-Kapitel einzelner MP3, Untertitel und Jahr aus den Tags (Etappe 4). 2: Ordnernamen, Begleitdateien, Serie und
     * Beschreibung aus den Tags (`books_improvements.md` M7).
     */
    private const val READ_VERSION = 2

    fun bookAuthor(book: LocalBookFiles): String =
        commonValue(book.files.map { it.albumArtist }) ?: commonValue(book.files.map { it.artist }).orEmpty()

    /**
     * Ändert sich, sobald eine Datei hinzukommt, wegfällt oder sich ändert – und mit [READ_VERSION], wenn das Einlesen
     * mehr liefert als vorher (dann wird jedes Buch einmal neu gelesen).
     */
    fun fingerprint(book: LocalBookFiles): Long {
        var hash = 1125899906842597L + READ_VERSION
        for (file in book.files) {
            hash = 31 * hash + "${file.id}|${file.sizeBytes}|${file.dateModifiedSec}".hashCode()
        }
        return hash
    }
}
