package de.kleinabersmart.earloft.wear.sync

import com.google.android.gms.wearable.DataClient
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearSelectionStatusDto
import de.kleinabersmart.earloft.core.wearsync.WearStatusDto
import de.kleinabersmart.earloft.core.wearsync.putPayload
import de.kleinabersmart.earloft.wear.download.WatchState
import de.kleinabersmart.earloft.wear.download.budgetReference
import de.kleinabersmart.earloft.wear.download.WearDownloadPlanner
import de.kleinabersmart.earloft.wear.download.downloadBudget
import de.kleinabersmart.earloft.wear.download.shouldReplanForRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports storage (songs, episodes and books) and progress per selection to the phone ([WearPaths.WATCH_STATUS], Wear concept 4). The
 * report is throttled (at most every [THROTTLE_MS]): while downloading, the progress changes with every song.
 */
@Singleton
class WatchStatusPublisher @Inject constructor(
    private val dataClient: DataClient,
    private val wearDao: WearDao,
    private val storage: DownloadStorage,
    private val state: WatchState,
    private val bookDao: BookDao,
    private val planner: WearDownloadPlanner,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                wearDao.observeSelectionProgress(),
                state.missingForStorage,
                state.lastError,
                state.settings,
                bookDao.observeTrackTotals(),
            ) { progress, _, _, _, _ ->
                progress
            }
                .conflate()
                .collect { progress ->
                    publish(progress.map { WearSelectionStatusDto(it.key, it.done, it.total, it.failed) })
                    delay(THROTTLE_MS)
                }
        }
    }

    /** Last sent report; only a different one is sent (review G47). */
    private var last: WearStatusDto? = null

    /** Download budget to measure freed space from ([shouldReplanForRoom], [budgetReference]). */
    private var lastBudget: Long? = null

    private suspend fun publish(selections: List<WearSelectionStatusDto>) {
        val settings = state.settings.value
        val used = wearDao.getDoneBytes() + bookDao.trackTotals().doneBytes
        val free = withContext(Dispatchers.IO) { storage.freeBytes() }
        // Room again after a full storage (or for titles left out): "storage full" is no longer true, and what it kept out
        // downloads by itself instead of waiting for "Retry" or the next change on the phone.
        val budget = downloadBudget(settings, free, used, reservedBytes = 0)
        val replan = shouldReplanForRoom(budget, state.isStorageFull, state.missingForStorage.value, lastBudget, FREE_BYTES_STEP)
        lastBudget = budgetReference(lastBudget, budget, replan)
        if (replan) {
            state.clearStorageFullError()
            planner.plan()
        }
        val status = WearStatusDto(
            usedBytes = used,
            freeBytes = free,
            limitBytes = settings.storageLimitBytes,
            selections = selections,
            missingForStorage = state.missingForStorage.value,
            lastError = state.lastError.value,
            updatedAt = System.currentTimeMillis(),
        )
        // While a file is being written, the free space and the progress of the running file (not in the report) change
        // all the time: without a real change the phone isn't woken every 10 s.
        val previous = last
        if (previous != null && previous.copy(updatedAt = status.updatedAt, freeBytes = status.freeBytes) == status &&
            kotlin.math.abs(previous.freeBytes - status.freeBytes) < FREE_BYTES_STEP
        ) {
            return
        }
        dataClient.putPayload(WearPaths.WATCH_STATUS, WearCodec.encode(status), urgent = false)
        last = status
    }

    private companion object {
        const val THROTTLE_MS = 10_000L
        const val FREE_BYTES_STEP = 50L * 1024 * 1024
    }
}
