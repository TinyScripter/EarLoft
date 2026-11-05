package de.kleinabersmart.kleinaberlaut.core.data.sync

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.database.dao.PlaylistDao
import de.kleinabersmart.kleinaberlaut.core.database.dao.SyncDao
import de.kleinabersmart.kleinaberlaut.core.database.entity.SyncActionEntity
import de.kleinabersmart.kleinaberlaut.core.datasource.RemoteLibraryActions
import de.kleinabersmart.kleinaberlaut.core.model.SyncActionType
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.HttpStatusException
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.NotSubsonicException
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.SubsonicException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Startet die Abarbeitung der Sync-Queue (Konzept 6.4). */
interface SyncScheduler {
    suspend fun enqueue()
}

@Singleton
internal class WorkManagerSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SyncScheduler {
    override suspend fun enqueue() {
        val request = OneTimeWorkRequestBuilder<SyncQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val WORK_NAME = "sync-queue"
    }
}

private val Context.syncSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_settings")

@Singleton
class SyncSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Gehörte Titel an den Server melden (Navidrome leitet sie ggf. an Last.fm/ListenBrainz weiter). */
    val scrobble: Flow<Boolean> = context.syncSettingsDataStore.data.map { it[KEY_SCROBBLE] ?: true }

    suspend fun setScrobble(enabled: Boolean) {
        context.syncSettingsDataStore.edit { it[KEY_SCROBBLE] = enabled }
    }

    private companion object {
        val KEY_SCROBBLE = booleanPreferencesKey("scrobble")
    }
}

/**
 * Sendet die gesammelten Aktionen an die Server. Ist ein Server nicht erreichbar, bleiben seine
 * Aktionen liegen, die anderer Server werden trotzdem gesendet. Aktionen, die der Server ablehnt
 * (z. B. Song gelöscht), oder die zu oft gescheitert sind, werden verworfen.
 */
class SyncQueueProcessor @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val syncDao: SyncDao,
    private val playlistDao: PlaylistDao,
    private val playlistSyncer: PlaylistSyncer,
    private val sources: MusicDataSources,
    private val notices: SyncNotices,
) {
    /** @return `true`, wenn nichts mehr aussteht, das später erneut versucht werden sollte. */
    suspend fun process(): Boolean {
        val unreachable = mutableSetOf<String>()
        for (action in syncDao.getActions()) {
            if (action.sourceId in unreachable) continue
            val remote = sources[action.sourceId] as? RemoteLibraryActions
            if (remote == null) {
                // Server entfernt oder nicht mehr nutzbar (z. B. Passwort nicht entschlüsselbar).
                syncDao.deleteAction(action.actionId)
                continue
            }
            try {
                execute(action, remote)
                syncDao.deleteAction(action.actionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRetryable(e) && action.attempts + 1 < MAX_ATTEMPTS) {
                    Log.w(TAG, "${action.actionType} an ${action.sourceId} später erneut", e)
                    syncDao.incrementAttempts(action.actionId)
                    unreachable += action.sourceId
                } else {
                    Log.w(TAG, "${action.actionType} an ${action.sourceId} verworfen", e)
                    syncDao.deleteAction(action.actionId)
                    noticeKind(action.actionType)?.let { notices.post(SyncNotice.ChangeDropped(it)) }
                }
            }
        }
        return unreachable.isEmpty()
    }

    private suspend fun execute(action: SyncActionEntity, remote: RemoteLibraryActions) {
        when (action.actionType) {
            SyncActionType.SET_FAVORITE -> {
                // Song-Quelle inzwischen gelöscht (Abgleich) – nichts mehr zu tun.
                val songSource = syncDao.getSongSource(action.targetKey) ?: return
                val favorite = action.payloadJson.toBoolean()
                remote.setFavorite(songSource.remoteId, favorite)
                syncDao.setStarredAt(songSource.uid, if (favorite) System.currentTimeMillis() else null)
            }
            SyncActionType.SCROBBLE -> {
                val songSource = syncDao.getSongSource(action.targetKey) ?: return
                remote.scrobble(songSource.remoteId, action.payloadJson!!.toLong())
            }
            SyncActionType.UPSERT_PLAYLIST -> upsertPlaylist(action.sourceId, action.targetKey.toLong(), remote)
            // Die lokale Playlist ist schon gelöscht, die Remote-ID steht deshalb im Payload.
            SyncActionType.DELETE_PLAYLIST -> remote.deletePlaylist(action.payloadJson!!)
        }
    }

    /**
     * Sendet Name und Songs; Songs ohne Quelle auf diesem Server fehlen dort. Wurde die Playlist auf dem
     * Server seit dem letzten Abgleich geändert, gewinnt der Server (Konzept 6.4) und nichts wird gesendet.
     */
    private suspend fun upsertPlaylist(sourceId: String, playlistId: Long, remote: RemoteLibraryActions) {
        val local = playlistDao.getPlaylist(playlistId) ?: return
        val songs = playlistDao.getRemoteSongIds(playlistId, sourceId)
        val remoteId = local.remotePlaylistId
        val info = if (remoteId == null) {
            remote.createPlaylist(local.name, songs)
        } else {
            val current = remote.playlist(remoteId)
            if (current.info.changedAt != local.remoteUpdatedAt) {
                Log.i(TAG, "Playlist ${local.name} wurde auf dem Server geändert – Server gewinnt")
                database.withTransaction { playlistSyncer.applyRemote(sourceId, playlistId, current) }
                return
            }
            remote.replacePlaylist(current, local.name, songs)
        }
        if (playlistDao.setRemoteState(playlistId, info.remoteId, info.changedAt) == 0 && remoteId == null) {
            // Während des Anlegens lokal gelöscht: sonst käme sie beim nächsten Abgleich zurück.
            remote.deletePlaylist(info.remoteId)
        }
    }

    /** Scrobbles und Löschen einer Playlist, die es dort nicht gibt, sind für den Nutzer keine Meldung wert. */
    private fun noticeKind(type: SyncActionType): SyncNotice.ChangeDropped.Kind? = when (type) {
        SyncActionType.SET_FAVORITE -> SyncNotice.ChangeDropped.Kind.FAVORITE
        SyncActionType.UPSERT_PLAYLIST -> SyncNotice.ChangeDropped.Kind.PLAYLIST
        SyncActionType.SCROBBLE, SyncActionType.DELETE_PLAYLIST -> null
    }

    /** Spezielle Fälle zuerst: Alle Subsonic- und HTTP-Fehler sind ebenfalls `IOException`s. */
    private fun isRetryable(e: Exception): Boolean = when (e) {
        is HttpStatusException -> e.status >= 500 || e.status == 429
        // Falsches Passwort: liegen lassen, bis es korrigiert ist.
        is SubsonicException -> e.isAuthError
        is NotSubsonicException -> true
        is IOException -> true
        else -> false
    }

    private companion object {
        const val TAG = "SyncQueue"
        const val MAX_ATTEMPTS = 20
    }
}

class SyncQueueWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncQueueEntryPoint::class.java)
        return if (entryPoint.processor().process()) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface SyncQueueEntryPoint {
        fun processor(): SyncQueueProcessor
    }
}

/** Stößt die Queue an, falls noch etwas aussteht (z. B. nach App-Start oder Abgleich). */
class SyncQueueStarter @Inject constructor(
    private val syncDao: SyncDao,
    private val scheduler: SyncScheduler,
) {
    suspend fun startIfPending() {
        if (syncDao.countActions() > 0) scheduler.enqueue()
    }
}

/** Reiht eine Aktion ein und fasst sie mit früheren für dasselbe Ziel zusammen. */
internal suspend fun SyncDao.replaceAction(sourceId: String, type: SyncActionType, targetKey: String, payload: String?) {
    deleteActions(sourceId, type, targetKey)
    insert(
        SyncActionEntity(
            sourceId = sourceId,
            actionType = type,
            targetKey = targetKey,
            payloadJson = payload,
            createdAt = System.currentTimeMillis(),
        ),
    )
}

@Module
@InstallIn(SingletonComponent::class)
internal interface SyncQueueBindings {
    @Binds
    fun bindScheduler(scheduler: WorkManagerSyncScheduler): SyncScheduler
}
