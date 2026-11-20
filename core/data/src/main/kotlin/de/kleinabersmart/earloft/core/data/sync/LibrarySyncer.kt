package de.kleinabersmart.earloft.core.data.sync

import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.data.repository.mergeCollectionStars
import de.kleinabersmart.earloft.core.datasource.RemoteStarred
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.RemoteLibraryActions
import de.kleinabersmart.earloft.core.datasource.RemoteQuickSync
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full sync of a source with mark-and-sweep (concept 6.3): each run increments the generation;
 * song sources that still carry an older generation afterwards no longer exist.
 */
@Singleton
class LibrarySyncer @Inject constructor(
    private val database: EarLoftDatabase,
    private val sourceDao: SourceDao,
    private val mergeDao: MergeDao,
    private val libraryDao: LibraryDao,
    private val mergeResolver: MergeResolver,
    private val changeListeners: Set<@JvmSuppressWildcards LibraryChangeListener>,
    private val syncQueue: SyncQueueStarter,
    private val playlistSyncer: PlaylistSyncer,
    private val syncDao: SyncDao,
    private val sweepGuard: SweepGuard,
) {
    private val mutex = Mutex()
    private val runningSyncs = MutableStateFlow(0)
    private val failedSyncs = MutableStateFlow<Map<String, SyncFailure>>(emptyMap())

    /** Only in memory: after a restart `lastFullSyncAt` suffices as the boundary. Access only under [mutex]. */
    private val lastQuickSyncAt = HashMap<String, Long>()

    /** Number of running syncs; > 0 means "library is being updated". */
    val activeSyncs: StateFlow<Int> = runningSyncs.asStateFlow()

    /** Error of the respective last sync per `sourceId`; a successful sync removes the entry. */
    val failures: StateFlow<Map<String, SyncFailure>> = failedSyncs.asStateFlow()

    /**
     * Like [sync], but catches errors and remembers them in [failures]. @return `true` on success; `false` also if the sweep
     * of music on the device was postponed (then the scan has to run again, review G6).
     */
    suspend fun syncSafely(source: MusicDataSource): Boolean =
        try {
            val complete = sync(source)
            failedSyncs.update { it - source.sourceId }
            complete
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync of ${source.sourceId} failed", e)
            failedSyncs.update { it + (source.sourceId to SyncFailure.of(e)) }
            false
        }

    /**
     * Cheap intermediate sync of a server (only [RemoteQuickSync] sources that have been fully synced
     * at least once): new albums, favorites and playlists. Never deletes anything and leaves the generation unchanged.
     * Skipped if a sync is currently running. Errors are only logged – an intermediate sync is optional.
     * @return `true` if it ran.
     */
    suspend fun quickSync(source: MusicDataSource): Boolean {
        if (source !is RemoteQuickSync) return false
        if (!mutex.tryLock()) return false
        try {
            val entity = sourceDao.get(source.sourceId)
            if (entity == null || entity.lastFullSyncAt == 0L) return false
            try {
                // With a margin for clocks that differ; merge already recognizes what's known.
                val since = maxOf(entity.lastFullSyncAt, lastQuickSyncAt[source.sourceId] ?: 0L) - CLOCK_SLACK_MS
                val added = source.tracksAddedSince(since)
                val starred = source.starred()
                database.withTransaction {
                    added.forEach { mergeResolver.merge(source.sourceId, it, entity.syncGeneration, syncsFavorites = true) }
                    mergeResolver.mergeStarred(source.sourceId, starred.songs)
                    applyCollectionStars(source.sourceId, starred)
                }
                if (source is RemoteLibraryActions) playlistSyncer.sync(source.sourceId, source, afterFullSync = false)
                syncQueue.startIfPending()
                if (added.isNotEmpty()) changeListeners.forEach { it.onSyncFinished() }
                lastQuickSyncAt[source.sourceId] = System.currentTimeMillis()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Intermediate sync of ${source.sourceId} failed", e)
                return false
            }
        } finally {
            mutex.unlock()
        }
    }

    /** @return `false` if the sweep of music on the device was postponed ([SweepGuard], review G6). */
    suspend fun sync(source: MusicDataSource): Boolean = mutex.withLock {
        runningSyncs.update { it + 1 }
        try {
            if (source.type.isServer) {
                // Removed while waiting for the mutex: don't bring it back as a server without address along with its library.
                if (sourceDao.get(source.sourceId) == null) return@withLock true
            } else {
                sourceDao.insertIfAbsent(SourceEntity(sourceId = source.sourceId, type = source.type, name = source.displayName))
            }
            val generation = (sourceDao.get(source.sourceId)?.syncGeneration ?: 0) + 1
            val syncsFavorites = source.type.isServer

            val isUnchanged: suspend (String, Long) -> Boolean = { remoteId, lastModified ->
                mergeDao.getSongSource("${source.sourceId}:$remoteId")?.lastModified == lastModified
            }
            val known = if (source.type == SourceType.LOCAL) mergeDao.countSongSources(source.sourceId) else 0
            var complete = true
            var listed = 0
            source.fullScan(isUnchanged).collect { batch ->
                if (!batch.complete) complete = false
                listed += batch.tracks.size
                database.withTransaction {
                    batch.tracks.forEach { mergeResolver.merge(source.sourceId, it, generation, syncsFavorites) }
                }
            }
            // Music on the device: an ejected SD card or a media index being rebuilt delivers a successful but incomplete
            // list. Then nothing is removed (review G6); the songs seen are already updated.
            if (source.type == SourceType.LOCAL && !(complete && sweepGuard.allows(LOCAL_MUSIC_AREA, known, listed))) {
                Log.w(TAG, "Sweep of ${source.sourceId} postponed ($listed of $known songs seen, complete: $complete)")
                // The generation still moves on, so the next scan compares against what this one saw.
                sourceDao.setSyncGeneration(source.sourceId, generation, System.currentTimeMillis())
                changeListeners.forEach { it.onSyncFinished() }
                return@withLock false
            }

            // Only clean up after a complete scan – an aborted scan must not delete anything.
            val removed = database.withTransaction {
                mergeResolver.adoptChangedIds(source.sourceId, generation)
                val removed = mergeDao.deleteStaleSongSources(source.sourceId, generation)
                mergeDao.deleteStaleAlbumSources(source.sourceId, generation)
                mergeDao.deleteStaleArtistSources(source.sourceId, generation)
                libraryDao.cleanupOrphans()
                sourceDao.setSyncGeneration(source.sourceId, generation, System.currentTimeMillis())
                removed
            }
            if (removed > 0) changeListeners.forEach { it.onSongSourcesRemoved() }
            // The songs' stars came with the songs; those of albums and artists are only in the server's list.
            if (source is RemoteQuickSync) syncCollectionStars(source)
            // Playlists only now: their entries refer to the songs just synced.
            if (source is RemoteLibraryActions) playlistSyncer.sync(source.sourceId, source)
            // The sync may have enqueued favorites for new server sources.
            syncQueue.startIfPending()
            changeListeners.forEach { it.onSyncFinished() }
            true
        } finally {
            runningSyncs.update { it - 1 }
        }
    }

    /** Optional like the intermediate sync: if it fails, the next one catches up. */
    private suspend fun syncCollectionStars(source: RemoteQuickSync) {
        val starred = try {
            source.starred()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Album/artist favorites not loaded", e)
            return
        }
        database.withTransaction { applyCollectionStars((source as MusicDataSource).sourceId, starred) }
    }

    private suspend fun applyCollectionStars(sourceId: String, starred: RemoteStarred) {
        syncDao.mergeCollectionStars(FavoriteKind.ALBUM, sourceId, starred.albums)
        syncDao.mergeCollectionStars(FavoriteKind.ARTIST, sourceId, starred.artists)
    }

    /**
     * Removes everything [sourceId] has delivered (local music turned off). Songs with other sources
     * stay. Deliberately does **not** wait for [mutex]: a long server sync shouldn't delay turning it
     * off. The caller must ensure that no sync of this source is running or starting anymore
     * (see [LibraryRefresher.start]); each batch transaction of another sync is atomic on its own.
     */
    suspend fun removeSource(sourceId: String) {
        if (sourceDao.get(sourceId) == null) return
        database.withTransaction {
            sourceDao.delete(sourceId)
            libraryDao.cleanupOrphans()
        }
        failedSyncs.update { it - sourceId }
        changeListeners.forEach { it.onSongSourcesRemoved() }
    }

    private companion object {
        const val TAG = "LibrarySyncer"
        const val LOCAL_MUSIC_AREA = "music"
        const val CLOCK_SLACK_MS = 60 * 60 * 1000L
    }
}
