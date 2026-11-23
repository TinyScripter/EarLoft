package de.kleinabersmart.earloft.core.download

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for downloaded audiobook files, separate from music (`downloads`) and episodes (`episodes`). As there, in
 * `noBackupFilesDir`. A book can have multiple files: `book-<bookId>-<trackIndex>.<extension>`.
 *
 * An interrupted file stays as `.part` so the next attempt continues there; next to it is a
 * `.part.meta` with the server file's fingerprint (see [ResumableTransfer]).
 */
// `usableSpace` only as the default for tests and for [freeBytes] (shown free space); what a download may use goes through
// `systemAllocatableBytes` (`getAllocatableBytes`, review H7). Suppressed so that real cases stand out (review2 J100).
@SuppressLint("UsableSpace")
@Singleton
class BookStorage internal constructor(
    val directory: File,
    private val allocatable: (File) -> Long = { it.usableSpace },
) : PartFiles {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, "books"), { directory -> systemAllocatableBytes(context, directory) })

    fun fileFor(bookId: Long, trackIndex: Int, mimeType: String?): File =
        File(directory, "${prefix(bookId)}$trackIndex.${DownloadStorage.extensionFor(mimeType)}")

    /**
     * Like [fileFor], but never a path from [taken] (the files of the book's other tracks): a downloaded file keeps
     * its path when it moves to a different index on the server; downloading the new file at its old index
     * would otherwise silently overwrite it (review D2). Then `book-<bookId>-<trackIndex>-<n>.<extension>`.
     */
    fun freeFileFor(bookId: Long, trackIndex: Int, mimeType: String?, taken: Collection<String>): File {
        val used = taken.mapTo(HashSet()) { File(it).name }
        val extension = DownloadStorage.extensionFor(mimeType)
        return generateSequence(1) { it + 1 }
            .map { n -> File(directory, if (n == 1) "${prefix(bookId)}$trackIndex.$extension" else "${prefix(bookId)}$trackIndex-$n.$extension") }
            .first { it.name !in used }
    }

    /** Target during the download; only renamed after a complete download. */
    override fun partFileFor(bookId: Long, trackIndex: Int): File = File(directory, "${prefix(bookId)}$trackIndex$PART_SUFFIX")

    /** Fingerprint of the server file the partial file belongs to. */
    override fun metaFileFor(bookId: Long, trackIndex: Int): File = File(directory, "${prefix(bookId)}$trackIndex$META_SUFFIX")

    /**
     * Partial file of a transfer from the phone (watch), separate from the server one ([partFileFor]); like that one it
     * stays after an interruption and disappears with [deleteParts].
     */
    fun phonePartFileFor(bookId: Long, trackIndex: Int): File =
        File(directory, "${prefix(bookId)}$trackIndex${DownloadStorage.PHONE_PART_SUFFIX}")

    /** Total length of the file according to the phone, for [phonePartFileFor]. */
    fun phoneLengthFileFor(bookId: Long, trackIndex: Int): File =
        File(directory, "${prefix(bookId)}$trackIndex${DownloadStorage.PHONE_LENGTH_SUFFIX}")

    override fun ensureDirectory(): File = directory.apply { mkdirs() }

    override fun freeBytes(): Long = ensureDirectory().usableSpace

    override fun allocatableBytes(): Long = allocatable(ensureDirectory())

    /** Deletes all files and partial files of the book. */
    fun delete(bookId: Long) {
        directory.listFiles().orEmpty().filter { it.name.startsWith(prefix(bookId)) }.forEach { it.delete() }
    }

    /** Deletes finished files that no longer belong to any entry (book deleted on the server or rescanned). @return count. */
    fun deleteOrphans(knownFilePaths: Collection<String>): Int {
        val keep = knownFilePaths.mapTo(HashSet()) { File(it).name }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && !it.name.contains(PART_SUFFIX) && it.name !in keep }
            .count { it.delete() }
    }

    /** Deletes all partial files; applies when nothing is waiting anymore and therefore nobody resumes. */
    fun deleteParts(): Int = directory.listFiles().orEmpty().filter { it.name.contains(PART_SUFFIX) }.count { it.delete() }

    private fun prefix(bookId: Long) = "book-$bookId-"

    private companion object {
        const val PART_SUFFIX = ".part"
        const val META_SUFFIX = ".part.meta"
    }
}
