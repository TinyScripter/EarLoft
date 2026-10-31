package keinabersmart.kleinaberlaut.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ablage der heruntergeladenen Hörbuch-Dateien, getrennt von Musik (`downloads`) und Folgen (`episodes`). Wie dort in
 * `noBackupFilesDir`. Ein Buch kann mehrere Dateien haben: `book-<bookId>-<trackIndex>.<Endung>`.
 */
@Singleton
class BookStorage internal constructor(val directory: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "books"))

    fun fileFor(bookId: Long, trackIndex: Int, mimeType: String?): File =
        File(directory, "${prefix(bookId)}$trackIndex.${DownloadStorage.extensionFor(mimeType)}")

    /** Ziel während des Ladens; wird erst nach vollständigem Download umbenannt. */
    fun partFileFor(bookId: Long, trackIndex: Int): File = File(directory, "${prefix(bookId)}$trackIndex$PART_SUFFIX")

    fun ensureDirectory(): File = directory.apply { mkdirs() }

    fun freeBytes(): Long = ensureDirectory().usableSpace

    /** Löscht alle Dateien und Teildateien des Buchs. */
    fun delete(bookId: Long) {
        directory.listFiles().orEmpty().filter { it.name.startsWith(prefix(bookId)) }.forEach { it.delete() }
    }

    /** Löscht Dateien, die zu keinem Eintrag mehr gehören (Buch vom Server gelöscht oder neu eingelesen). @return Anzahl. */
    fun deleteOrphans(knownFilePaths: Collection<String>): Int {
        val keep = knownFilePaths.mapTo(HashSet()) { File(it).name }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) && it.name !in keep }
            .count { it.delete() }
    }

    /** Teildateien, die kein Lauf mehr anfasst (z. B. nach Prozessende), gehören dem nächsten Lauf nicht. */
    fun deleteParts(): Int = directory.listFiles().orEmpty().filter { it.name.endsWith(PART_SUFFIX) }.count { it.delete() }

    private fun prefix(bookId: Long) = "book-$bookId-"

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
