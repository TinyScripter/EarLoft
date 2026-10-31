package keinabersmart.kleinaberlaut.core.data.spoken

import android.util.Log
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält Hörbücher aktuell: Vollabgleich beim Start (> 6 h), beim Öffnen der App der Fortschritt des Kontos
 * (höchstens alle 10 min), auf Anforderung alles.
 */
@Singleton
class SpokenRefresher @Inject constructor(
    private val syncer: BookSyncer,
    private val progressSync: BookProgressSync,
    private val servers: AudiobookshelfServers,
    private val dao: BookDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastQuick = AtomicLong(0)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val now = System.currentTimeMillis()
            progressSync.pushPending()
            servers.all()
                .filter { now - (dao.server(it.serverId)?.lastFullSyncAt ?: 0) > FULL_SYNC_INTERVAL_MS }
                .forEach { syncer.syncSafely(it) }
        }
    }

    /** Beim Öffnen der App: Fortschritt, den andere Geräte geschrieben haben, übernehmen. */
    fun refreshQuick() {
        val now = System.currentTimeMillis()
        val last = lastQuick.get()
        if (now - last < QUICK_INTERVAL_MS || !lastQuick.compareAndSet(last, now)) return
        scope.launch {
            progressSync.pushPending()
            servers.all().forEach { server ->
                try {
                    progressSync.pullAll(server)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Fortschritt von ${server.baseUrl} nicht abrufbar", e)
                }
            }
        }
    }

    fun refreshAll() {
        scope.launch { syncer.syncAll() }
    }

    private companion object {
        const val TAG = "SpokenRefresher"
        const val FULL_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val QUICK_INTERVAL_MS = 10 * 60 * 1000L
    }
}
