package de.kleinabersmart.earloft.core.download

import java.io.File

/**
 * Wo [ResumableTransfer] eine unterbrochene Datei ablegt: die Teildatei und daneben das Merkmal der Serverdatei. Bücher
 * ([BookStorage]) haben mehrere Dateien je Buch, Folgen ([EpisodeStorage]) nur eine (`trackIndex` 0).
 */
interface PartFiles {
    fun partFileFor(bookId: Long, trackIndex: Int): File
    fun metaFileFor(bookId: Long, trackIndex: Int): File
    fun ensureDirectory(): File
    fun freeBytes(): Long

    /** Verwirft die Teildatei samt Merkmal; der nächste Versuch beginnt von vorn. */
    fun deletePart(bookId: Long, trackIndex: Int) {
        partFileFor(bookId, trackIndex).delete()
        metaFileFor(bookId, trackIndex).delete()
    }
}
