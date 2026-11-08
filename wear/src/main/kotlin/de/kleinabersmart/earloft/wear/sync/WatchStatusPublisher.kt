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
 * Meldet dem Handy Speicher (Songs, Folgen und Bücher) und Fortschritt je Auswahl ([WearPaths.WATCH_STATUS], Wear-Konzept 4). Die
 * Meldung wird gedrosselt (höchstens alle [THROTTLE_MS]): Beim Laden ändert sich der Fortschritt mit jedem Song.
 */
@Singleton
class WatchStatusPublisher @Inject constructor(
    private val dataClient: DataClient,
    private val wearDao: WearDao,
    private val storage: DownloadStorage,
    private val state: WatchState,
    private val bookDao: BookDao,
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

    private suspend fun publish(selections: List<WearSelectionStatusDto>) {
        val settings = state.settings.value
        val used = wearDao.getDoneBytes() + bookDao.trackTotals().doneBytes
        val free = withContext(Dispatchers.IO) { storage.freeBytes() }
        val status = WearStatusDto(
            usedBytes = used,
            freeBytes = free,
            limitBytes = settings.storageLimitBytes,
            selections = selections,
            missingForStorage = state.missingForStorage.value,
            lastError = state.lastError.value,
            updatedAt = System.currentTimeMillis(),
        )
        dataClient.putPayload(WearPaths.WATCH_STATUS, WearCodec.encode(status), urgent = false)
    }

    private companion object {
        const val THROTTLE_MS = 10_000L
    }
}
