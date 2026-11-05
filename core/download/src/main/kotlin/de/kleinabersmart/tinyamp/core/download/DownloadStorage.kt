package de.kleinabersmart.tinyamp.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.database.entity.DownloadEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ablage der heruntergeladenen Dateien. Sie liegen in `noBackupFilesDir` und werden daher weder
 * gesichert noch per Geräteübertragung kopiert (Konzept 10, Sicherheit).
 */
@Singleton
class DownloadStorage internal constructor(val directory: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "downloads"))

    fun fileFor(sourceUid: String, mimeType: String?): File =
        File(directory, "${safeName(sourceUid)}.${extensionFor(mimeType)}")

    /** Ziel während des Ladens; wird erst nach vollständigem Download umbenannt. */
    fun partFileFor(sourceUid: String): File = File(directory, "${safeName(sourceUid)}$PART_SUFFIX")

    fun ensureDirectory(): File = directory.apply { mkdirs() }

    fun freeBytes(): Long = ensureDirectory().usableSpace

    fun delete(download: DownloadEntity) {
        download.filePath?.let { File(it).delete() }
        partFileFor(download.sourceUid).delete()
    }

    /** Löscht Dateien, zu denen es keinen Eintrag in `downloads` mehr gibt. @return Anzahl. */
    fun deleteOrphans(downloads: List<DownloadEntity>): Int {
        val keep = downloads.flatMapTo(HashSet()) { download ->
            listOfNotNull(download.filePath?.let { File(it).name }, partFileFor(download.sourceUid).name)
        }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.name !in keep }
            .count { it.delete() }
    }

    internal companion object {
        const val PART_SUFFIX = ".part"

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
