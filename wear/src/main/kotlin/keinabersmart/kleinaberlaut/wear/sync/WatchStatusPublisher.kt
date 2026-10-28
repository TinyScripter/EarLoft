package keinabersmart.kleinaberlaut.wear.sync

import com.google.android.gms.wearable.DataClient
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.download.DownloadStorage
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearSelectionStatusDto
import keinabersmart.kleinaberlaut.core.wearsync.WearStatusDto
import keinabersmart.kleinaberlaut.core.wearsync.putPayload
import keinabersmart.kleinaberlaut.wear.download.WatchState
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
 * Meldet dem Handy Speicher und Fortschritt je Auswahl ([WearPaths.WATCH_STATUS], Wear-Konzept 4). Die
 * Meldung wird gedrosselt (höchstens alle [THROTTLE_MS]): Beim Laden ändert sich der Fortschritt mit jedem Song.
 */
@Singleton
class WatchStatusPublisher @Inject constructor(
    private val dataClient: DataClient,
    private val wearDao: WearDao,
    private val storage: DownloadStorage,
    private val state: WatchState,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(wearDao.observeSelectionProgress(), state.missingForStorage, state.lastError, state.settings) { progress, _, _, _ ->
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
        val (used, free) = withContext(Dispatchers.IO) { wearDao.getDoneBytes() to storage.freeBytes() }
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
