package de.kleinabersmart.earloft.core.download

import java.io.File

/**
 * Where [ResumableTransfer] stores an interrupted file: the partial file and next to it the server file's fingerprint. Books
 * ([BookStorage]) have multiple files per book, episodes ([EpisodeStorage]) only one (`trackIndex` 0).
 */
interface PartFiles {
    fun partFileFor(bookId: Long, trackIndex: Int): File
    fun metaFileFor(bookId: Long, trackIndex: Int): File
    fun ensureDirectory(): File
    fun freeBytes(): Long

    /** What a new download may use; see [systemAllocatableBytes]. */
    fun allocatableBytes(): Long = freeBytes()

    /** Discards the partial file including its fingerprint; the next attempt starts from scratch. */
    fun deletePart(bookId: Long, trackIndex: Int) {
        partFileFor(bookId, trackIndex).delete()
        metaFileFor(bookId, trackIndex).delete()
    }
}
