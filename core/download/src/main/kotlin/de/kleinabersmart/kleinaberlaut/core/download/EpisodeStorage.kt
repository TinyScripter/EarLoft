package de.kleinabersmart.kleinaberlaut.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ablage der heruntergeladenen Podcast-Folgen, getrennt von der Musik (`downloads`). Wie dort in `noBackupFilesDir`:
 * weder gesichert noch per Geräteübertragung kopiert.
 */
@Singleton
class EpisodeStorage internal constructor(val directory: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "episodes"))

    fun fileFor(bookId: Long, mimeType: String?): File =
        File(directory, "episode-$bookId.${DownloadStorage.extensionFor(mimeType)}")

    /** Ziel während des Ladens; wird erst nach vollständigem Download umbenannt. */
    fun partFileFor(bookId: Long): File = File(directory, "episode-$bookId$PART_SUFFIX")

    fun ensureDirectory(): File = directory.apply { mkdirs() }

    fun freeBytes(): Long = ensureDirectory().usableSpace

    /** Löscht Datei und Teildatei der Folge. */
    fun delete(bookId: Long, filePath: String?) {
        filePath?.let { File(it).delete() }
        partFileFor(bookId).delete()
    }

    /** Löscht Dateien, die zu keinem Eintrag mehr gehören (z. B. nach Abo-Ende). @return Anzahl. */
    fun deleteOrphans(knownFilePaths: Collection<String>, bookIdsWithPart: Collection<Long>): Int {
        val keep = HashSet<String>()
        knownFilePaths.mapTo(keep) { File(it).name }
        bookIdsWithPart.mapTo(keep) { partFileFor(it).name }
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.name !in keep }
            .count { it.delete() }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
