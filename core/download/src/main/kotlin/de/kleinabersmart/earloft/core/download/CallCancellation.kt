package de.kleinabersmart.earloft.core.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException

/**
 * Runs [block], which executes this call. Cancelling the calling coroutine cancels the call at once, also while it blocks in
 * a read: a completion handler only ran once the job had ended, i.e. after the read timed out (up to 30 s), and with REPLACE
 * the old and the new download ran side by side until then (review H31). The read that the cancelled call breaks off ends as
 * a cancellation, not as a network error.
 */
internal suspend fun <T> Call.cancelledWithCoroutine(block: suspend () -> T): T = coroutineScope {
    val call = this@cancelledWithCoroutine
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        block()
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        watcher.cancel()
    }
}
