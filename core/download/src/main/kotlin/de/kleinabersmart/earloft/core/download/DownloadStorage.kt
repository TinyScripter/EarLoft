package de.kleinabersmart.earloft.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for downloaded files. They live in `noBackupFilesDir` and are therefore neither
 * backed up nor copied by device transfer (concept 10, security).
 */
@Singleton
class DownloadStorage internal constructor(val directory: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "downloads"))

    fun fileFor(sourceUid: String, mimeType: String?): File =
        File(directory, "${safeName(sourceUid)}.${extensionFor(mimeType)}")

    /** Target during the download; only renamed after a complete download. */
    fun partFileFor(sourceUid: String): File = File(directory, "${safeName(sourceUid)}$PART_SUFFIX")

    /**
     * Partial file of a transfer from the phone (watch, Wear concept 8.4). Separate from [partFileFor] because the server may
     * send a different version (Opus); it stays after an interruption so the next attempt can continue.
     */
    fun phonePartFileFor(sourceUid: String): File = File(directory, "${safeName(sourceUid)}$PHONE_PART_SUFFIX")

    /** Total length of the file according to the phone, for [phonePartFileFor]. */
    fun phoneLengthFileFor(sourceUid: String): File = File(directory, "${safeName(sourceUid)}$PHONE_LENGTH_SUFFIX")

    fun ensureDirectory(): File = directory.apply { mkdirs() }

    fun freeBytes(): Long = ensureDirectory().usableSpace

    fun delete(download: DownloadEntity) {
        download.filePath?.let { File(it).delete() }
        partFileFor(download.sourceUid).delete()
        phonePartFileFor(download.sourceUid).delete()
        phoneLengthFileFor(download.sourceUid).delete()
    }

    /**
     * Deletes files that no longer have an entry in `downloads`, and started transfers from the phone for
     * entries that have been finished in the meantime. Fresh files stay: the janitor runs after every sync alongside the
     * download worker, and a just-renamed file may not have its path in [downloads] yet. @return count.
     */
    fun deleteOrphans(downloads: List<DownloadEntity>, nowMs: Long = System.currentTimeMillis()): Int {
        val keep = downloads.flatMapTo(HashSet()) { download ->
            val uid = download.sourceUid
            val phoneParts = if (download.filePath == null) listOf(phonePartFileFor(uid).name, phoneLengthFileFor(uid).name) else emptyList()
            listOfNotNull(download.filePath?.let { File(it).name }, partFileFor(uid).name) + phoneParts
        }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.name !in keep && nowMs - it.lastModified() > FRESH_FILE_MS }
            .count { it.delete() }
    }

    internal companion object {
        const val FRESH_FILE_MS = 60_000L
        const val PART_SUFFIX = ".part"
        const val PHONE_PART_SUFFIX = ".phone.part"
        const val PHONE_LENGTH_SUFFIX = ".phone.part.len"

        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

        fun safeName(sourceUid: String): String = sourceUid.replace(UNSAFE_CHARS, "_")

        fun extensionFor(mimeType: String?): String = when (mimeType?.lowercase()) {
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg", "audio/vorbis" -> "ogg"
            "audio/opus" -> "opus"
            "audio/mp4", "audio/aac", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> "audio"
        }
    }
}
