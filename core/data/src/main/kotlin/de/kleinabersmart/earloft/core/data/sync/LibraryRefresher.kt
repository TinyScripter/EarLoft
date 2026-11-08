package de.kleinabersmart.earloft.core.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvent
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvents
import de.kleinabersmart.earloft.core.data.refresh.RefreshKind
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.datasource.local.LocalDataSource
import de.kleinabersmart.earloft.core.datasource.local.MediaStoreChanges
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
 * - **Lokal:** beim Start des Prozesses nur, wenn sich der MediaStore seit dem letzten Scan geändert hat
 *   ([LocalScanStamp], ab API 30; darunter beim Öffnen der App), auf Anforderung (z. B. nach Erteilen der
 *   Berechtigung) und gebündelt nach Änderungen im MediaStore – nur mit [LibrarySettings.useLocalMedia]. Wird die
 *   Einstellung abgeschaltet, bricht ein laufender Scan ab und die lokalen Songs verschwinden sofort aus der
 *   Bibliothek. Lokal abgeglichen wird ausschließlich hier.
 * - **Server:** beim Öffnen der App ([refreshQuick]) ein Vollabgleich, wenn der letzte länger als
 *   [SERVER_SYNC_INTERVAL_MS] her ist, sonst ein Zwischenabgleich; regelmäßig (täglich/wöchentlich/monatlich/nie,
 *   [LibrarySettings.syncIntervalDays]) per [ServerSyncWorker] und auf Anforderung. Nicht beim bloßen Start des
 *   Prozesses: Der kommt auch für Hintergrund-Jobs und Nachrichten der Uhr, und dort gelten die Netz-Bedingungen der Worker.
 */
@Singleton
class LibraryRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val syncer: LibrarySyncer,
    private val localDataSource: LocalDataSource,
    private val registry: DataSourceRegistry,
    private val sourceDao: SourceDao,
    private val newSongs: NewSongsCounter,
    private val mediaStoreChanges: MediaStoreChanges,
    private val librarySettings: LibrarySettings,
    private val scanStamp: LocalScanStamp,
    private val events: RefreshEvents,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastQuickAttempt = AtomicLong(0)
    private val _refreshing = MutableStateFlow(false)

    /** `true`, solange ein Abgleich läuft, den der Nutzer angestoßen hat (für Pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    // replay = 1: Eine Anforderung, bevor der Scan-Ablauf läuft (Öffnen der App direkt nach dem Prozessstart), geht nicht verloren.
    private val localRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Abschalten bricht den Scan ab (collectLatest wartet, bis er beendet ist) und entfernt dann – so kann
        // kein Scan danach noch lokale Songs anlegen, und niemand wartet auf einen langen Server-Abgleich.
        scope.launch {
            var atProcessStart = true
            librarySettings.useLocalMedia.distinctUntilChanged().collectLatest { enabled ->
                val initial = atProcessStart
                atProcessStart = false
                if (!enabled) {
                    scanStamp.clear()
                    syncer.removeSource(localDataSource.sourceId)
                    return@collectLatest
                }
                merge(localRequests, mediaStoreChanges.observe())
                    // Beim Einschalten immer; beim Prozessstart nur bei geändertem MediaStore (unbekannt: refreshQuick).
                    .onStart { if (!initial || scanStamp.changedSinceLastScan() == true) emit(Unit) }
                    .debounce(DEBOUNCE_MS)
                    .collect { syncLocal() }
            }
        }
        scope.launch {
            librarySettings.syncIntervalDays.distinctUntilChanged().collect { ServerSyncWorker.schedule(context, it) }
        }
    }

    /** Lokale Musik neu einlesen. */
    fun refreshNow() {
        localRequests.tryEmit(Unit)
    }

    /** Der Stand von vor dem Scan wird gemerkt: Was sich währenddessen ändert, scannt der nächste Start. */
    private suspend fun syncLocal() {
        val stamp = scanStamp.current()
        if (syncer.syncSafely(localDataSource)) scanStamp.save(stamp)
    }

    /**
     * Beim Öffnen der App, höchstens alle [QUICK_SYNC_INTERVAL_MS]: Vollabgleich der Server, die ihn nötig haben, und
     * Zwischenabgleich der übrigen (neue Alben, Favoriten und Playlists anderer Clients). Unter API 30 außerdem die
     * lokale Musik, weil der Prozessstart dort nicht erkennt, ob sie sich geändert hat.
     */
    fun refreshQuick() {
        val now = System.currentTimeMillis()
        val last = lastQuickAttempt.get()
        if (now - last < QUICK_SYNC_INTERVAL_MS || !lastQuickAttempt.compareAndSet(last, now)) return
        scope.launch {
            if (librarySettings.useLocalMedia.first() && scanStamp.changedSinceLastScan() == null) refreshNow()
            val threshold = fullSyncThresholdMs() ?: return@launch
            val (due, recent) = registry.servers().partition { now - (sourceDao.get(it.sourceId)?.lastFullSyncAt ?: 0) > threshold }
            due.forEach { syncer.syncSafely(it) }
            recent.forEach { syncer.quickSync(it) }
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
                // Neu heißt: nach dem Start angelegt. Beim ersten Einlesen einer Quelle ist alles neu und wird nicht gezählt.
                val baseline = newSongs.begin(activeSourceIds())
                refreshNow()
                registry.servers().forEach { syncer.syncSafely(it) }
                // Der lokale Scan startet erst nach der Entprellung; bis dahin soll der Spinner nicht kurz ausgehen.
                if (librarySettings.useLocalMedia.first()) delay(DEBOUNCE_MS + LOCAL_START_SLACK_MS)
                syncer.activeSyncs.first { it == 0 }
                val fresh = newSongs.newSongs(baseline)
                events.emit(RefreshEvent.Finished(RefreshKind.MUSIC, fresh, failed = syncer.failures.value.isNotEmpty()))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Quellen, die ein Abgleich gerade berührt: alle Server und, wenn an, die lokale Musik. */
    private suspend fun activeSourceIds(): List<String> =
        registry.servers().map { it.sourceId } +
            if (librarySettings.useLocalMedia.first()) listOf(localDataSource.sourceId) else emptyList()

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
