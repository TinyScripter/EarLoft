package keinabersmart.kleinaberlaut.core.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.datasource.local.LocalDataSource
import keinabersmart.kleinaberlaut.core.datasource.local.MediaStoreChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die Bibliothek aktuell.
 *
 * - **Lokal:** beim Start, auf Anforderung (z. B. nach Erteilen der Berechtigung) und gebündelt
 *   nach Änderungen im MediaStore – nur mit [LibrarySettings.useLocalMedia]. Wird die Einstellung
 *   abgeschaltet, bricht ein laufender Scan ab und die lokalen Songs verschwinden sofort aus der
 *   Bibliothek. Lokal abgeglichen wird ausschließlich hier.
 * - **Server:** beim Start, wenn der letzte Vollabgleich länger als [SERVER_SYNC_INTERVAL_MS] her
 *   ist, täglich per [ServerSyncWorker] und auf Anforderung; sonst beim Öffnen der App ein
 *   Zwischenabgleich ([refreshQuick]).
 */
@Singleton
class LibraryRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val syncer: LibrarySyncer,
    private val localDataSource: LocalDataSource,
    private val registry: DataSourceRegistry,
    private val sourceDao: SourceDao,
    private val mediaStoreChanges: MediaStoreChanges,
    private val librarySettings: LibrarySettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastQuickAttempt = AtomicLong(0)
    private val localRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Abschalten bricht den Scan ab (collectLatest wartet, bis er beendet ist) und entfernt dann – so kann
        // kein Scan danach noch lokale Songs anlegen, und niemand wartet auf einen langen Server-Abgleich.
        scope.launch {
            librarySettings.useLocalMedia.distinctUntilChanged().collectLatest { enabled ->
                if (!enabled) {
                    syncer.removeSource(localDataSource.sourceId)
                    return@collectLatest
                }
                merge(localRequests, mediaStoreChanges.observe())
                    .onStart { emit(Unit) }
                    .debounce(DEBOUNCE_MS)
                    .collect { syncer.syncSafely(localDataSource) }
            }
        }

        scope.launch {
            val now = System.currentTimeMillis()
            registry.servers()
                .filter { now - (sourceDao.get(it.sourceId)?.lastFullSyncAt ?: 0) > SERVER_SYNC_INTERVAL_MS }
                .forEach { syncer.syncSafely(it) }
        }
        ServerSyncWorker.schedule(context)
    }

    /** Lokale Musik neu einlesen. */
    fun refreshNow() {
        localRequests.tryEmit(Unit)
    }

    /**
     * Zwischenabgleich aller Server, deren Vollabgleich nicht ohnehin ansteht (neue Alben, Favoriten und
     * Playlists anderer Clients). Für den Start der App gedacht; höchstens alle [QUICK_SYNC_INTERVAL_MS].
     */
    fun refreshQuick() {
        val now = System.currentTimeMillis()
        val last = lastQuickAttempt.get()
        if (now - last < QUICK_SYNC_INTERVAL_MS || !lastQuickAttempt.compareAndSet(last, now)) return
        scope.launch {
            registry.servers()
                .filter { now - (sourceDao.get(it.sourceId)?.lastFullSyncAt ?: 0) <= SERVER_SYNC_INTERVAL_MS }
                .forEach { syncer.quickSync(it) }
        }
    }

    /** Eine einzelne Quelle sofort abgleichen. */
    fun refreshSource(sourceId: String) {
        scope.launch { registry[sourceId]?.let { syncer.syncSafely(it) } }
    }

    /** Alle Quellen sofort abgleichen; die lokale über den eigenen Ablauf (siehe [start]). */
    fun refreshAll() {
        refreshNow()
        scope.launch { registry.servers().forEach { syncer.syncSafely(it) } }
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
        const val SERVER_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val QUICK_SYNC_INTERVAL_MS = 10 * 60 * 1000L
    }
}
