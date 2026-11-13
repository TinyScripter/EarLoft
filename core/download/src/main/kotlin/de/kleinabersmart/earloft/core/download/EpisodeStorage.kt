package de.kleinabersmart.earloft.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ablage der heruntergeladenen Podcast-Folgen, getrennt von der Musik (`downloads`). Wie dort in `noBackupFilesDir`:
 * weder gesichert noch per Geräteübertragung kopiert.
 *
 * Eine unterbrochene Datei bleibt als `.part` liegen, solange die Folge wartet; daneben das Merkmal der Serverdatei
 * (`.part.meta`, siehe [ResumableTransfer]).
 */
@Singleton
class EpisodeStorage internal constructor(val directory: File) : PartFiles {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "episodes"))

    fun fileFor(bookId: Long, mimeType: String?): File =
        File(directory, "episode-$bookId.${DownloadStorage.extensionFor(mimeType)}")

    /** Ziel während des Ladens; wird erst nach vollständigem Download umbenannt. */
    fun partFileFor(bookId: Long): File = File(directory, "episode-$bookId$PART_SUFFIX")

    /** Merkmal der Serverdatei, zu der die Teildatei gehört. */
    fun metaFileFor(bookId: Long): File = File(directory, "episode-$bookId$META_SUFFIX")

    override fun partFileFor(bookId: Long, trackIndex: Int): File = partFileFor(bookId)

    override fun metaFileFor(bookId: Long, trackIndex: Int): File = metaFileFor(bookId)

    /** Teildatei einer Übertragung vom Handy (Uhr), wie [DownloadStorage.phonePartFileFor]. */
    fun phonePartFileFor(bookId: Long): File = File(directory, "episode-$bookId${DownloadStorage.PHONE_PART_SUFFIX}")

    /** Gesamtlänge der Datei laut Handy, zu [phonePartFileFor]. */
    fun phoneLengthFileFor(bookId: Long): File = File(directory, "episode-$bookId${DownloadStorage.PHONE_LENGTH_SUFFIX}")

    override fun ensureDirectory(): File = directory.apply { mkdirs() }

    override fun freeBytes(): Long = ensureDirectory().usableSpace

    /** Löscht Datei und Teildatei der Folge. */
    fun delete(bookId: Long, filePath: String?) {
        filePath?.let { File(it).delete() }
        partFileFor(bookId).delete()
        metaFileFor(bookId).delete()
        phonePartFileFor(bookId).delete()
        phoneLengthFileFor(bookId).delete()
    }

    /** Löscht Dateien, die zu keinem Eintrag mehr gehören (z. B. nach Abo-Ende). @return Anzahl. */
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
