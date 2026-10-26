package keinabersmart.kleinaberlaut.core.data.sync

import android.util.Log
import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.MergeDao
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.datasource.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.datasource.MusicDataSource
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
    private val database: KleinaberlautDatabase,
    private val sourceDao: SourceDao,
    private val mergeDao: MergeDao,
    private val libraryDao: LibraryDao,
    private val mergeResolver: MergeResolver,
    private val registry: DataSourceRegistry,
) {
    private val mutex = Mutex()
    private val runningSyncs = MutableStateFlow(0)

    /** Anzahl laufender Abgleiche; > 0 bedeutet „Bibliothek wird aktualisiert“. */
    val activeSyncs: StateFlow<Int> = runningSyncs.asStateFlow()

    /** Gleicht alle Quellen ab; Fehler einer Quelle brechen die anderen nicht ab. */
    suspend fun syncAll() {
        registry.all().forEach { source ->
            try {
                sync(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Abgleich von ${source.sourceId} fehlgeschlagen", e)
            }
        }
    }

    suspend fun sync(source: MusicDataSource) = mutex.withLock {
        runningSyncs.update { it + 1 }
        try {
            sourceDao.insertIfAbsent(
                SourceEntity(sourceId = source.sourceId, type = source.type, name = source.displayName),
            )
            val generation = (sourceDao.get(source.sourceId)?.syncGeneration ?: 0) + 1

            source.fullScan().collect { batch ->
                database.withTransaction {
                    batch.tracks.forEach { mergeResolver.merge(source.sourceId, it, generation) }
                }
            }

            // Erst nach vollständigem Scan aufräumen – ein abgebrochener Scan darf nichts löschen.
            database.withTransaction {
                mergeDao.deleteStaleSongSources(source.sourceId, generation)
                libraryDao.cleanupOrphans()
                sourceDao.setSyncGeneration(source.sourceId, generation, System.currentTimeMillis())
            }
        } finally {
            runningSyncs.update { it - 1 }
        }
    }

    private companion object {
        const val TAG = "LibrarySyncer"
    }
}
