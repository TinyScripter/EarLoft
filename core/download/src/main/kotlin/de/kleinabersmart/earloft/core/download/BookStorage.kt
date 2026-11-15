package de.kleinabersmart.earloft.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ablage der heruntergeladenen Hörbuch-Dateien, getrennt von Musik (`downloads`) und Folgen (`episodes`). Wie dort in
 * `noBackupFilesDir`. Ein Buch kann mehrere Dateien haben: `book-<bookId>-<trackIndex>.<Endung>`.
 *
 * Eine unterbrochene Datei bleibt als `.part` liegen, damit der nächste Versuch dort weitermacht; daneben liegt eine
 * `.part.meta` mit dem Merkmal der Serverdatei (siehe [ResumableTransfer]).
 */
@Singleton
class BookStorage internal constructor(val directory: File) : PartFiles {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "books"))

    fun fileFor(bookId: Long, trackIndex: Int, mimeType: String?): File =
        File(directory, "${prefix(bookId)}$trackIndex.${DownloadStorage.extensionFor(mimeType)}")

    /**
     * Wie [fileFor], aber nie ein Pfad aus [taken] (die Dateien der übrigen Spuren des Buchs): Eine geladene Datei behält
     * ihren Pfad, wenn sie auf dem Server an einen anderen Index rückt; der Download der neuen Datei auf ihrem alten Index
     * überschriebe sie sonst still (Review D2). Dann `book-<bookId>-<trackIndex>-<n>.<Endung>`.
     */
    fun freeFileFor(bookId: Long, trackIndex: Int, mimeType: String?, taken: Collection<String>): File {
        val used = taken.mapTo(HashSet()) { File(it).name }
        val extension = DownloadStorage.extensionFor(mimeType)
        return generateSequence(1) { it + 1 }
            .map { n -> File(directory, if (n == 1) "${prefix(bookId)}$trackIndex.$extension" else "${prefix(bookId)}$trackIndex-$n.$extension") }
            .first { it.name !in used }
    }

    /** Ziel während des Ladens; wird erst nach vollständigem Download umbenannt. */
    override fun partFileFor(bookId: Long, trackIndex: Int): File = File(directory, "${prefix(bookId)}$trackIndex$PART_SUFFIX")

    /** Merkmal der Serverdatei, zu der die Teildatei gehört. */
    override fun metaFileFor(bookId: Long, trackIndex: Int): File = File(directory, "${prefix(bookId)}$trackIndex$META_SUFFIX")

    /**
     * Teildatei einer Übertragung vom Handy (Uhr), getrennt von der des Servers ([partFileFor]); wie diese bleibt sie nach
     * einem Abbruch liegen und verschwindet mit [deleteParts].
     */
    fun phonePartFileFor(bookId: Long, trackIndex: Int): File =
        File(directory, "${prefix(bookId)}$trackIndex${DownloadStorage.PHONE_PART_SUFFIX}")

    /** Gesamtlänge der Datei laut Handy, zu [phonePartFileFor]. */
    fun phoneLengthFileFor(bookId: Long, trackIndex: Int): File =
        File(directory, "${prefix(bookId)}$trackIndex${DownloadStorage.PHONE_LENGTH_SUFFIX}")

    override fun ensureDirectory(): File = directory.apply { mkdirs() }

    override fun freeBytes(): Long = ensureDirectory().usableSpace

    /** Löscht alle Dateien und Teildateien des Buchs. */
    fun delete(bookId: Long) {
        directory.listFiles().orEmpty().filter { it.name.startsWith(prefix(bookId)) }.forEach { it.delete() }
    }

    /** Löscht fertige Dateien, die zu keinem Eintrag mehr gehören (Buch vom Server gelöscht oder neu eingelesen). @return Anzahl. */
    fun deleteOrphans(knownFilePaths: Collection<String>): Int {
        val keep = knownFilePaths.mapTo(HashSet()) { File(it).name }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && !it.name.contains(PART_SUFFIX) && it.name !in keep }
            .count { it.delete() }
    }

    /** Löscht alle Teildateien; gilt, wenn nichts mehr wartet und daher keiner mehr weitermacht. */
    fun deleteParts(): Int = directory.listFiles().orEmpty().filter { it.name.contains(PART_SUFFIX) }.count { it.delete() }

    private fun prefix(bookId: Long) = "book-$bookId-"

    private companion object {
        const val PART_SUFFIX = ".part"
        const val META_SUFFIX = ".part.meta"
    }
}
