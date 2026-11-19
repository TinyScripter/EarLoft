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
 * Keeps the library up to date.
 *
 * - **Local:** at process start only if the MediaStore has changed since the last scan
 *   ([LocalScanStamp], from API 30; below that when opening the app), on request (e.g. after granting the
 *   permission) and batched after changes in the MediaStore – only with [LibrarySettings.useLocalMedia]. If the
 *   setting is turned off, a running scan is aborted and the local songs disappear from the library immediately.
 *   Local syncing happens exclusively here.
 * - **Server:** when opening the app ([refreshQuick]) a full sync if the last one was more than
 *   [SERVER_SYNC_INTERVAL_MS] ago, otherwise an intermediate sync; periodically (daily/weekly/monthly/never,
 *   [LibrarySettings.syncIntervalDays]) via [ServerSyncWorker] and on request. Not on a mere process
 *   start: that also happens for background jobs and messages from the watch, and there the workers' network constraints apply.
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

    /** `true` while a sync triggered by the user is running (for pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    // replay = 1: a request before the scan flow runs (opening the app right after process start) isn't lost.
    private val localRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Turning off aborts the scan (collectLatest waits until it has finished) and then removes – so no
        // scan can create local songs afterwards, and nobody waits for a long server sync.
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
                    // Always when turning on; at process start only if the MediaStore changed (unknown: refreshQuick).
                    .onStart { if (!initial || scanStamp.changedSinceLastScan() == true) emit(Unit) }
                    .debounce(DEBOUNCE_MS)
                    .collect { syncLocal() }
            }
        }
        scope.launch {
            librarySettings.syncIntervalDays.distinctUntilChanged().collect { ServerSyncWorker.schedule(context, it) }
        }
    }

    /** Re-read local music. */
    fun refreshNow() {
        localRequests.tryEmit(Unit)
    }

    /** The state from before the scan is remembered: whatever changes in the meantime is scanned on the next start. */
    private suspend fun syncLocal() {
        val stamp = scanStamp.current()
        if (syncer.syncSafely(localDataSource)) scanStamp.save(stamp)
    }

    /**
     * When opening the app, at most every [QUICK_SYNC_INTERVAL_MS]: full sync of the servers that need it, and
     * intermediate sync of the others (new albums, favorites and playlists from other clients). Below API 30 also the
     * local music, because process start there can't detect whether it has changed.
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

    /** Sync a single source immediately. */
    fun refreshSource(sourceId: String) {
        scope.launch { registry[sourceId]?.let { syncer.syncSafely(it) } }
    }

    /**
     * Sync all sources immediately; the local one via its own flow (see [start]). Reports start and end to the
     * UI; [refreshing] stays on until the local scan has finished too.
     */
    fun refreshAll() {
        scope.launch {
            _refreshing.value = true
            try {
                events.emit(RefreshEvent.Started(RefreshKind.MUSIC))
                // New means: created after the start. On the first read of a source everything is new and isn't counted.
                val baseline = newSongs.begin(activeSourceIds())
                refreshNow()
                registry.servers().forEach { syncer.syncSafely(it) }
                // The local scan only starts after the debounce; until then the spinner shouldn't briefly turn off.
                if (librarySettings.useLocalMedia.first()) delay(DEBOUNCE_MS + LOCAL_START_SLACK_MS)
                syncer.activeSyncs.first { it == 0 }
                val fresh = newSongs.newSongs(baseline)
                events.emit(RefreshEvent.Finished(RefreshKind.MUSIC, fresh, failed = syncer.failures.value.isNotEmpty()))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Sources a sync currently touches: all servers and, if on, local music. */
    private suspend fun activeSourceIds(): List<String> =
        registry.servers().map { it.sourceId } +
            if (librarySettings.useLocalMedia.first()) listOf(localDataSource.sourceId) else emptyList()

    /**
     * When a full sync is due at startup: with "daily" after 6 h, otherwise after the configured
     * interval; `null` = "never" (then no intermediate sync either, only manually).
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
