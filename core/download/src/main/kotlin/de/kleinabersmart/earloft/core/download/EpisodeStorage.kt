package de.kleinabersmart.earloft.core.download

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for downloaded podcast episodes, separate from music (`downloads`). As there, in `noBackupFilesDir`:
 * neither backed up nor copied by device transfer.
 *
 * An interrupted file stays as `.part` while the episode is waiting; next to it the server file's fingerprint
 * (`.part.meta`, see [ResumableTransfer]).
 */
// `usableSpace` only as the default for tests and for [freeBytes] (shown free space); what a download may use goes through
// `systemAllocatableBytes` (`getAllocatableBytes`, review H7). Suppressed so that real cases stand out (review2 J100).
@SuppressLint("UsableSpace")
@Singleton
class EpisodeStorage internal constructor(
    val directory: File,
    private val allocatable: (File) -> Long = { it.usableSpace },
) : PartFiles {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, "episodes"), { directory -> systemAllocatableBytes(context, directory) })

    fun fileFor(bookId: Long, mimeType: String?): File =
        File(directory, "episode-$bookId.${DownloadStorage.extensionFor(mimeType)}")

    /** Target during the download; only renamed after a complete download. */
    fun partFileFor(bookId: Long): File = File(directory, "episode-$bookId$PART_SUFFIX")

    /** Fingerprint of the server file the partial file belongs to. */
    fun metaFileFor(bookId: Long): File = File(directory, "episode-$bookId$META_SUFFIX")

    override fun partFileFor(bookId: Long, trackIndex: Int): File = partFileFor(bookId)

    override fun metaFileFor(bookId: Long, trackIndex: Int): File = metaFileFor(bookId)

    /** Partial file of a transfer from the phone (watch), like [DownloadStorage.phonePartFileFor]. */
    fun phonePartFileFor(bookId: Long): File = File(directory, "episode-$bookId${DownloadStorage.PHONE_PART_SUFFIX}")

    /** Total length of the file according to the phone, for [phonePartFileFor]. */
    fun phoneLengthFileFor(bookId: Long): File = File(directory, "episode-$bookId${DownloadStorage.PHONE_LENGTH_SUFFIX}")

    override fun ensureDirectory(): File = directory.apply { mkdirs() }

    override fun freeBytes(): Long = ensureDirectory().usableSpace

    override fun allocatableBytes(): Long = allocatable(ensureDirectory())

    /** Deletes the episode's file and partial file. */
    fun delete(bookId: Long, filePath: String?) {
        filePath?.let { File(it).delete() }
        partFileFor(bookId).delete()
        metaFileFor(bookId).delete()
        phonePartFileFor(bookId).delete()
        phoneLengthFileFor(bookId).delete()
    }

    /** Deletes files that no longer belong to any entry (e.g. after a subscription ends). @return count. */
    fun deleteOrphans(knownFilePaths: Collection<String>, bookIdsWithPart: Collection<Long>): Int {
        val keep = HashSet<String>()
        knownFilePaths.mapTo(keep) { File(it).name }
        bookIdsWithPart.forEach { bookId ->
            keep += partFileFor(bookId).name
            keep += metaFileFor(bookId).name
            keep += phonePartFileFor(bookId).name
            keep += phoneLengthFileFor(bookId).name
        }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.name !in keep }
            .count { it.delete() }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val META_SUFFIX = ".part.meta"
    }
}
