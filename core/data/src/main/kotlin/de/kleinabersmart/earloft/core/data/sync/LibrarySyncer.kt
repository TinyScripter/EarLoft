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
 * Vollabgleich einer Quelle mit Mark-and-Sweep (Konzept 6.3): Jeder Lauf erhöht die Generation;
 * Song-Quellen, die danach noch eine ältere Generation tragen, gibt es nicht mehr.
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
) {
    private val mutex = Mutex()
    private val runningSyncs = MutableStateFlow(0)
    private val failedSyncs = MutableStateFlow<Map<String, SyncFailure>>(emptyMap())

    /** Nur im Speicher: nach einem Neustart genügt `lastFullSyncAt` als Grenze. Zugriff nur unter [mutex]. */
    private val lastQuickSyncAt = HashMap<String, Long>()

    /** Anzahl laufender Abgleiche; > 0 bedeutet „Bibliothek wird aktualisiert“. */
    val activeSyncs: StateFlow<Int> = runningSyncs.asStateFlow()

    /** Fehler des jeweils letzten Abgleichs je `sourceId`; ein erfolgreicher Abgleich entfernt den Eintrag. */
    val failures: StateFlow<Map<String, SyncFailure>> = failedSyncs.asStateFlow()

    /** Wie [sync], fängt aber Fehler ab und merkt sie sich in [failures]. @return `true` bei Erfolg. */
    suspend fun syncSafely(source: MusicDataSource): Boolean =
        try {
            sync(source)
            failedSyncs.update { it - source.sourceId }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Abgleich von ${source.sourceId} fehlgeschlagen", e)
            failedSyncs.update { it + (source.sourceId to SyncFailure.of(e)) }
            false
        }

    /**
     * Billiger Zwischenabgleich eines Servers (nur [RemoteQuickSync]-Quellen, die schon einmal voll abgeglichen
     * wurden): neue Alben, Favoriten und Playlists. Löscht nie etwas und lässt die Generation unverändert.
     * Läuft gerade ein Abgleich, entfällt er. Fehler werden nur geloggt – ein Zwischenabgleich ist optional.
     * @return `true`, wenn er gelaufen ist.
     */
    suspend fun quickSync(source: MusicDataSource): Boolean {
        if (source !is RemoteQuickSync) return false
        if (!mutex.tryLock()) return false
        try {
            val entity = sourceDao.get(source.sourceId)
            if (entity == null || entity.lastFullSyncAt == 0L) return false
            try {
                // Mit Reserve für abweichende Uhren; Merge erkennt bereits Bekanntes.
                val since = maxOf(entity.lastFullSyncAt, lastQuickSyncAt[source.sourceId] ?: 0L) - CLOCK_SLACK_MS
                val added = source.tracksAddedSince(since)
                val starred = source.starred()
                database.withTransaction {
                    added.forEach { mergeResolver.merge(source.sourceId, it, entity.syncGeneration, syncsFavorites = true) }
                    mergeResolver.mergeStarred(source.sourceId, starred.songs)
                    applyCollectionStars(source.sourceId, starred)
                }
                if (source is RemoteLibraryActions) playlistSyncer.sync(source.sourceId, source)
                syncQueue.startIfPending()
                if (added.isNotEmpty()) changeListeners.forEach { it.onSyncFinished() }
                lastQuickSyncAt[source.sourceId] = System.currentTimeMillis()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Zwischenabgleich von ${source.sourceId} fehlgeschlagen", e)
                return false
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun sync(source: MusicDataSource) = mutex.withLock {
        runningSyncs.update { it + 1 }
        try {
            if (source.type.isServer) {
                // Während des Wartens auf den Mutex entfernt: nicht als Server ohne Adresse samt Bibliothek zurückholen.
                if (sourceDao.get(source.sourceId) == null) return@withLock
            } else {
                sourceDao.insertIfAbsent(SourceEntity(sourceId = source.sourceId, type = source.type, name = source.displayName))
            }
            val generation = (sourceDao.get(source.sourceId)?.syncGeneration ?: 0) + 1
            val syncsFavorites = source.type.isServer

            val isUnchanged: suspend (String, Long) -> Boolean = { remoteId, lastModified ->
                mergeDao.getSongSource("${source.sourceId}:$remoteId")?.lastModified == lastModified
            }
            source.fullScan(isUnchanged).collect { batch ->
                database.withTransaction {
                    batch.tracks.forEach { mergeResolver.merge(source.sourceId, it, generation, syncsFavorites) }
                }
            }

            // Erst nach vollständigem Scan aufräumen – ein abgebrochener Scan darf nichts löschen.
            val removed = database.withTransaction {
                val removed = mergeDao.deleteStaleSongSources(source.sourceId, generation)
                mergeDao.deleteStaleAlbumSources(source.sourceId, generation)
                mergeDao.deleteStaleArtistSources(source.sourceId, generation)
                libraryDao.cleanupOrphans()
                sourceDao.setSyncGeneration(source.sourceId, generation, System.currentTimeMillis())
                removed
            }
            if (removed > 0) changeListeners.forEach { it.onSongSourcesRemoved() }
            // Die Sterne der Songs kamen mit den Songs; die der Alben und Künstler stehen nur in der Liste des Servers.
            if (source is RemoteQuickSync) syncCollectionStars(source)
            // Playlists erst jetzt: Ihre Einträge verweisen auf die gerade abgeglichenen Songs.
            if (source is RemoteLibraryActions) playlistSyncer.sync(source.sourceId, source)
            // Der Abgleich kann Favoriten für neue Server-Quellen eingereiht haben.
            syncQueue.startIfPending()
            changeListeners.forEach { it.onSyncFinished() }
        } finally {
            runningSyncs.update { it - 1 }
        }
    }

    /** Optional wie der Zwischenabgleich: Scheitert es, holt das der nächste nach. */
    private suspend fun syncCollectionStars(source: RemoteQuickSync) {
        val starred = try {
            source.starred()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Favoriten von Alben/Künstlern nicht geladen", e)
            return
        }
        database.withTransaction { applyCollectionStars((source as MusicDataSource).sourceId, starred) }
    }

    private suspend fun applyCollectionStars(sourceId: String, starred: RemoteStarred) {
        syncDao.mergeCollectionStars(FavoriteKind.ALBUM, sourceId, starred.albums)
        syncDao.mergeCollectionStars(FavoriteKind.ARTIST, sourceId, starred.artists)
    }

    /**
     * Entfernt alles, was [sourceId] geliefert hat (lokale Musik abgeschaltet). Songs mit weiteren Quellen
     * bleiben. Wartet bewusst **nicht** auf [mutex]: Ein langer Server-Abgleich soll das Abschalten nicht
     * verzögern. Der Aufrufer muss sicherstellen, dass kein Abgleich dieser Quelle mehr läuft oder startet
     * (siehe [LibraryRefresher.start]); jede Batch-Transaktion eines anderen Abgleichs ist für sich atomar.
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
        const val CLOCK_SLACK_MS = 60 * 60 * 1000L
    }
}
