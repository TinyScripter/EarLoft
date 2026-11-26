package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.database.dao.BookDownloadRow
import de.kleinabersmart.earloft.core.model.DownloadFailure
import de.kleinabersmart.earloft.core.model.TrackDownload

/**
 * Download state of a book with the size of the fully downloaded files. [doneFiles] of [totalFiles] are downloaded; with
 * [TrackDownload.FAILED] and finished files the book is [partial] – the finished ones play from the device, the rest streams.
 * [failure]: why it failed (episodes), if known.
 */
data class BookDownload(
    val state: TrackDownload,
    val bytes: Long,
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val failure: DownloadFailure? = null,
) {
    val partial: Boolean get() = state == TrackDownload.FAILED && doneFiles > 0
}

/**
 * State of a book from the state of its files: "running" before "waiting" before "failed" – a failed file doesn't hold
 * up the others; it's only complete once all files are complete. If all are removed, the book counts as removed.
 */
fun BookDownloadRow.state(): TrackDownload = when {
    running > 0 -> TrackDownload.RUNNING
    queued > 0 -> TrackDownload.QUEUED
    failed > 0 -> TrackDownload.FAILED
    total > 0 && done == total -> TrackDownload.DONE
    removed > 0 -> TrackDownload.REMOVED
    else -> TrackDownload.NONE
}

fun BookDownloadRow.toDownload(): BookDownload = BookDownload(state(), bytes, done, total)
