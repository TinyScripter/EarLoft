package de.kleinabersmart.earloft.core.download

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides within a worker run whether an interrupted file ([DownloadOutcome.Retry]) gives up or is re-enqueued
 * and the run retried with backoff. WorkManager counts the budget of [maxAttempts] runs per job, not per file: when it is
 * exhausted, only the run's first file gives up. Otherwise, with a server unreachable for a longer time, every
 * further file would fail immediately, and the automation would never download these books and episodes again.
 */
internal class RetryBudget(private val runAttemptCount: Int, private val maxAttempts: Int) {
    private val gaveUp = AtomicBoolean(false)

    /** `true`: the file gives up. `false`: enqueue and end the run with `Result.retry()`. */
    fun giveUp(outcome: DownloadOutcome.Retry): Boolean {
        // A rejected key doesn't change by waiting.
        if (outcome.isAuthRejection) return true
        if (runAttemptCount + 1 < maxAttempts) return false
        return !gaveUp.getAndSet(true)
    }
}
