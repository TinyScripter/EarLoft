package de.kleinabersmart.earloft.core.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Transferred bytes of a downloading book; [totalBytes] is `null` as long as the server doesn't report the size. */
data class BookTransfer(val doneBytes: Long, val totalBytes: Long?)

/**
 * Progress of the book currently downloading (in memory only, the worker reports it every half second), across all its
 * files. With multiple files the total size is an estimate from the files known so far.
 */
@Singleton
class BookDownloadProgress @Inject constructor() {
    private val _transfers = MutableStateFlow<Map<Long, BookTransfer>>(emptyMap())

    /** Book → state; only for books currently downloading. */
    val transfers: StateFlow<Map<Long, BookTransfer>> = _transfers.asStateFlow()

    /**
     * [filesDone] finished files out of [filesTotal] with [finishedBytes] combined; the current file has [done] of
     * [total] bytes.
     */
    internal fun report(bookId: Long, filesDone: Int, filesTotal: Int, finishedBytes: Long, done: Long, total: Long?) {
        val totalBytes = total?.let { (finishedBytes + it) * filesTotal / (filesDone + 1) }
        _transfers.update { it + (bookId to BookTransfer(finishedBytes + done, totalBytes)) }
    }

    internal fun clear(bookId: Long) {
        _transfers.update { it - bookId }
    }
}
