package de.kleinabersmart.earloft.core.download

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first

/**
 * Is the job [name] currently waiting in backoff (after `Result.retry()`, up to 5 h)? Otherwise a manually started download
 * would queue behind it with `APPEND_OR_REPLACE` and only start afterwards (review D5).
 */
internal suspend fun WorkManager.waitsInBackoff(name: String): Boolean =
    getWorkInfosForUniqueWorkFlow(name).first().any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }

/**
 * Policy for a manually started download: a job in backoff is replaced (the new one starts immediately, with a fresh
 * attempt budget), otherwise appended as usual; a running one picks up the new file anyway.
 */
internal suspend fun WorkManager.manualDownloadPolicy(name: String): ExistingWorkPolicy =
    if (waitsInBackoff(name)) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
