package de.kleinabersmart.earloft.core.download

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first

/**
 * Wartet der Auftrag [name] gerade im Backoff (nach `Result.retry()`, bis zu 5 h)? Ein von Hand gestarteter Download hängte
 * sich mit `APPEND_OR_REPLACE` sonst dahinter und begänne erst danach (Review D5).
 */
internal suspend fun WorkManager.waitsInBackoff(name: String): Boolean =
    getWorkInfosForUniqueWorkFlow(name).first().any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }

/**
 * Policy für einen von Hand gestarteten Download: Ein Auftrag im Backoff wird ersetzt (der neue beginnt sofort, mit frischem
 * Versuchsbudget), sonst wie gewohnt angehängt; ein laufender nimmt die neue Datei ohnehin noch mit.
 */
internal suspend fun WorkManager.manualDownloadPolicy(name: String): ExistingWorkPolicy =
    if (waitsInBackoff(name)) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
