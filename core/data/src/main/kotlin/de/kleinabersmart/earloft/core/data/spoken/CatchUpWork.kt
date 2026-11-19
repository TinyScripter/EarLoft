package de.kleinabersmart.earloft.core.data.spoken

import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker

/**
 * Common rules of the catch-up workers ([BookProgressWorker], [AbsSessionWorker]): they report what didn't reach
 * Audiobookshelf while offline, and retry with increasing intervals (30 s to 5 h).
 */
internal object CatchUpWork {

    /**
     * Runs after which it gives up (a good 8 h in total). Whatever is still pending then stays `dirty` and goes out with the
     * next sync or playback; otherwise a server that no longer exists or whose sign-in has expired would
     * retry forever (review D1).
     */
    const val MAX_ATTEMPTS = 10

    fun result(done: Boolean, runAttemptCount: Int): ListenableWorker.Result =
        if (done || runAttemptCount + 1 >= MAX_ATTEMPTS) ListenableWorker.Result.success() else ListenableWorker.Result.retry()

    /**
     * A running or waiting job stays: if sending fails within the worker itself, it reschedules itself that way;
     * with `REPLACE` that would cancel its own run and enqueue a new one immediately, without backoff (review D1). [replace]
     * only for user actions that should go out right away.
     */
    fun policy(replace: Boolean): ExistingWorkPolicy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
}
