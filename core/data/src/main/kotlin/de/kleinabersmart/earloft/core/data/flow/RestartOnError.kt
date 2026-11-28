package de.kleinabersmart.earloft.core.data.flow

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/**
 * For collectors that live as long as the process (publishing to the watch, the widget): an exception, e.g. a
 * `SQLiteFullException` on a full disk, used to end them silently until the next start (review5 P20). Instead it is logged and
 * the flow is collected again after [delayMs]. Put the side effect in `onEach` before this, not in `collect`: an exception thrown
 * downstream is not caught.
 */
fun <T> Flow<T>.restartOnError(tag: String, what: String, delayMs: Long = RESTART_DELAY_MS): Flow<T> = retryWhen { cause, _ ->
    if (cause is CancellationException) return@retryWhen false
    Log.w(tag, "$what failed, starting again in ${delayMs / 1000} s", cause)
    delay(delayMs)
    true
}

private const val RESTART_DELAY_MS = 30_000L
