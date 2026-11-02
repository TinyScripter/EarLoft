package keinabersmart.kleinaberlaut.core.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvent
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvents
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshKind
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.datasource.local.LocalDataSource
import keinabersmart.kleinaberlaut.core.datasource.local.MediaStoreChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
 *   ist, regelmäßig (täglich/wöchentlich/monatlich/nie, [LibrarySettings.syncIntervalDays]) per [ServerSyncWorker] und auf Anforderung; sonst beim Öffnen der App ein
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
    private val events: RefreshEvents,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastQuickAttempt = AtomicLong(0)
    private val _refreshing = MutableStateFlow(false)

    /** `true`, solange ein Abgleich läuft, den der Nutzer angestoßen hat (für Pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
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
            val threshold = fullSyncThresholdMs() ?: return@launch
            val now = System.currentTimeMillis()
            registry.servers()
                .filter { now - (sourceDao.get(it.sourceId)?.lastFullSyncAt ?: 0) > threshold }
                .forEach { syncer.syncSafely(it) }
        }
        scope.launch {
            librarySettings.syncIntervalDays.distinctUntilChanged().collect { ServerSyncWorker.schedule(context, it) }
        }
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
            val threshold = fullSyncThresholdMs() ?: return@launch
            registry.servers()
                .filter { now - (sourceDao.get(it.sourceId)?.lastFullSyncAt ?: 0) <= threshold }
                .forEach { syncer.quickSync(it) }
        }
    }

    /** Eine einzelne Quelle sofort abgleichen. */
    fun refreshSource(sourceId: String) {
        scope.launch { registry[sourceId]?.let { syncer.syncSafely(it) } }
    }

    /**
     * Alle Quellen sofort abgleichen; die lokale über den eigenen Ablauf (siehe [start]). Meldet Start und Ende an die
     * Oberfläche; [refreshing] bleibt an, bis auch der lokale Scan fertig ist.
     */
    fun refreshAll() {
        scope.launch {
            _refreshing.value = true
            try {
                events.emit(RefreshEvent.Started(RefreshKind.MUSIC))
                refreshNow()
                registry.servers().forEach { syncer.syncSafely(it) }
                // Der lokale Scan startet erst nach der Entprellung; bis dahin soll der Spinner nicht kurz ausgehen.
                if (librarySettings.useLocalMedia.first()) delay(DEBOUNCE_MS + LOCAL_START_SLACK_MS)
                syncer.activeSyncs.first { it == 0 }
                events.emit(RefreshEvent.Finished(RefreshKind.MUSIC, newCount = 0, failed = syncer.failures.value.isNotEmpty()))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Ab wann ein Vollabgleich beim Start fällig ist: bei „täglich“ nach 6 h, sonst nach dem eingestellten
     * Intervall; `null` = „nie“ (dann auch kein Zwischenabgleich, nur von Hand).
     */
    private suspend fun fullSyncThresholdMs(): Long? {
        val days = librarySettings.syncIntervalDays.first()
        return when {
            days <= LibrarySettings.SYNC_NEVER -> null
            days == 1 -> SERVER_SYNC_INTERVAL_MS
            else -> days * DAY_MS
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 1_500L
        const val LOCAL_START_SLACK_MS = 300L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val SERVER_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val QUICK_SYNC_INTERVAL_MS = 10 * 60 * 1000L
    }
}
