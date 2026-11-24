package de.kleinabersmart.earloft.core.data.sync

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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.repository.collectionStarState
import de.kleinabersmart.earloft.core.data.repository.setCollectionStarredAt
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.SyncActionEntity
import de.kleinabersmart.earloft.core.datasource.RemoteLibraryActions
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.SyncActionType
import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.ServerApiException
import de.kleinabersmart.earloft.core.network.UnexpectedResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Starts processing the sync queue (concept 6.4). */
interface SyncScheduler {
    /** New action: a running pass may not have seen it anymore, so one more afterwards. */
    suspend fun enqueue()

    /** Catch up on pending items: a waiting or running pass suffices. */
    suspend fun resume() = enqueue()
}

@Singleton
internal class WorkManagerSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SyncScheduler {
    // Check and enqueue together: otherwise a second caller that also saw the backoff replaces the pass the first one just
    // started, and a playlist upload or scrobble is cut off halfway (review3 Q19).
    private val enqueueing = Mutex()

    // A pass waiting in backoff (unreachable server, up to 5 h) is replaced: appended, a new favorite for a reachable server
    // would wait behind it (review2 J17, like D5 for downloads). A waiting pass has not started, so nothing is cut off.
    override suspend fun enqueue() = enqueueing.withLock {
        enqueue(if (waitsInBackoff()) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private suspend fun waitsInBackoff(): Boolean = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
        .any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }

    // After every sync: otherwise APPEND would each time append another pass to one waiting in backoff.
    override suspend fun resume() = enqueueing.withLock { enqueue(ExistingWorkPolicy.KEEP) }

    // Awaited: the next caller's check must see this pass.
    private suspend fun enqueue(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SyncQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request).await()
    }

    private companion object {
        const val WORK_NAME = "sync-queue"
    }
}

internal val Context.syncSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_settings")

@Singleton
class SyncSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Report played tracks to the server (Navidrome forwards them to Last.fm/ListenBrainz if configured). */
    val scrobble: Flow<Boolean> = context.syncSettingsDataStore.data.map { it[KEY_SCROBBLE] ?: true }

    suspend fun setScrobble(enabled: Boolean) {
        context.syncSettingsDataStore.edit { it[KEY_SCROBBLE] = enabled }
    }

    private companion object {
        val KEY_SCROBBLE = booleanPreferencesKey("scrobble")
    }
}

/**
 * Sends the collected actions to the servers. If a server is unreachable, its
 * actions stay queued; those of other servers are sent anyway. Actions the server rejects
 * (e.g. song deleted), or that failed too often, are discarded.
 */
class SyncQueueProcessor @Inject constructor(
    private val database: EarLoftDatabase,
    private val syncDao: SyncDao,
    private val playlistDao: PlaylistDao,
    private val playlistSyncer: PlaylistSyncer,
    private val sources: MusicDataSources,
    private val notices: SyncNotices,
) {
    /** @return `true` if nothing is pending anymore that should be retried later. */
    suspend fun process(): Boolean {
        val unreachable = mutableSetOf<String>()
        for (action in syncDao.getActions()) {
            if (action.sourceId in unreachable) continue
            val remote = sources[action.sourceId] as? RemoteLibraryActions
            if (remote == null) {
                // The server still exists (its removal deletes its actions), but is not usable right now: switched off or its
                // password can't be decrypted (e.g. keystore after a system update, G43). Kept like for an unreachable server
                // until the password is entered again (review2 Z8), dropped with a notice only after the same time.
                if (System.currentTimeMillis() - action.createdAt < MAX_UNREACHABLE_AGE_MS) {
                    unreachable += action.sourceId
                } else {
                    Log.w(TAG, "${action.actionType} to unusable ${action.sourceId} discarded")
                    syncDao.deleteAction(action.actionId)
                    noticeKind(action.actionType)?.let { notices.post(SyncNotice.ChangeDropped(it)) }
                }
                continue
            }
            try {
                execute(action, remote)
                syncDao.deleteAction(action.actionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (keepsAfter(e, action)) {
                    Log.w(TAG, "${action.actionType} to ${action.sourceId}: retrying later", e)
                    // An unreachable server or a wrong password doesn't use up the attempts (review G23); the first failure is
                    // still counted, because an aborted playlist upload must win next time (see upsertPlaylist).
                    if (failureOf(e) == Failure.SERVER || action.attempts == 0) syncDao.incrementAttempts(action.actionId)
                    unreachable += action.sourceId
                } else {
                    Log.w(TAG, "${action.actionType} to ${action.sourceId} discarded", e)
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
                // Song source deleted in the meantime (sync) – nothing more to do.
                val songSource = syncDao.getSongSource(action.targetKey) ?: return
                val favorite = action.payloadJson.toBoolean()
                remote.setFavorite(songSource.remoteId, favorite)
                syncDao.setStarredAt(songSource.uid, if (favorite) System.currentTimeMillis() else null)
            }
            SyncActionType.SET_ALBUM_FAVORITE -> setCollectionFavorite(FavoriteKind.ALBUM, action, remote)
            SyncActionType.SET_ARTIST_FAVORITE -> setCollectionFavorite(FavoriteKind.ARTIST, action, remote)
            SyncActionType.SCROBBLE -> {
                val songSource = syncDao.getSongSource(action.targetKey) ?: return
                remote.scrobble(songSource.remoteId, action.payloadJson!!.toLong())
            }
            SyncActionType.UPSERT_PLAYLIST -> PlaylistSyncLock.mutex.withLock { upsertPlaylist(action, remote) }
            // The local playlist is already deleted, so the remote ID is in the payload.
            SyncActionType.DELETE_PLAYLIST -> PlaylistSyncLock.mutex.withLock { remote.deletePlaylist(action.payloadJson!!) }
        }
    }

    /** As with songs; `targetKey` is the `remoteId` of the album or artist on this server. */
    private suspend fun setCollectionFavorite(kind: FavoriteKind, action: SyncActionEntity, remote: RemoteLibraryActions) {
        // No longer on the server in the meantime (sync) – nothing more to do.
        syncDao.collectionStarState(kind, action.sourceId, action.targetKey) ?: return
        val favorite = action.payloadJson.toBoolean()
        remote.setFavorite(action.targetKey, favorite, kind)
        syncDao.setCollectionStarredAt(kind, action.sourceId, action.targetKey, if (favorite) System.currentTimeMillis() else null)
    }

    /**
     * Sends name and songs; songs without a source on this server are missing there. If the playlist was changed on the
     * server since the last sync, the server wins (concept 6.4) and nothing is sent –
     * except after a failed attempt: Emby changes in several steps, an aborted attempt leaves a
     * half-applied state there that looks like a change on the server. Then the local version wins.
     */
    private suspend fun upsertPlaylist(action: SyncActionEntity, remote: RemoteLibraryActions) {
        val sourceId = action.sourceId
        val playlistId = action.targetKey.toLong()
        val local = playlistDao.getPlaylist(playlistId) ?: return
        val songs = playlistDao.getRemoteSongIds(playlistId, sourceId)
        val remoteId = local.remotePlaylistId
        val info = if (remoteId == null) {
            remote.createPlaylist(local.name, songs)
        } else {
            val current = remote.playlist(remoteId)
            if (current.info.changedAt != local.remoteUpdatedAt && action.attempts == 0) {
                Log.i(TAG, "Playlist ${local.name} was changed on the server – server wins")
                database.withTransaction { playlistSyncer.applyRemote(sourceId, playlistId, current) }
                return
            }
            remote.replacePlaylist(current, local.name, songs)
        }
        if (playlistDao.setRemoteState(playlistId, info.remoteId, info.changedAt) == 0 && remoteId == null) {
            // Deleted locally during creation: otherwise it would come back on the next sync.
            remote.deletePlaylist(info.remoteId)
        }
    }

    /** Scrobbles and deleting a playlist that doesn't exist there aren't worth a message to the user. */
    private fun noticeKind(type: SyncActionType): SyncNotice.ChangeDropped.Kind? = when (type) {
        SyncActionType.SET_FAVORITE, SyncActionType.SET_ALBUM_FAVORITE, SyncActionType.SET_ARTIST_FAVORITE ->
            SyncNotice.ChangeDropped.Kind.FAVORITE
        SyncActionType.UPSERT_PLAYLIST -> SyncNotice.ChangeDropped.Kind.PLAYLIST
        SyncActionType.SCROBBLE, SyncActionType.DELETE_PLAYLIST -> null
    }

    /**
     * Whether [action] stays queued after [e] (review G23): after a server error up to [MAX_ATTEMPTS] tries; while the server is
     * unreachable (only at home, phone elsewhere) or the password is wrong, up to [MAX_UNREACHABLE_AGE_MS] after it was
     * queued, so a few days away don't lose favorites, playlist changes and scrobbles.
     */
    private fun keepsAfter(e: Exception, action: SyncActionEntity): Boolean = when (failureOf(e)) {
        Failure.SERVER -> action.attempts + 1 < MAX_ATTEMPTS
        Failure.UNREACHABLE -> System.currentTimeMillis() - action.createdAt < MAX_UNREACHABLE_AGE_MS
        Failure.FINAL -> false
    }

    private enum class Failure { SERVER, UNREACHABLE, FINAL }

    /** Special cases first: all server and HTTP errors are `IOException`s too. */
    private fun failureOf(e: Exception): Failure = when (e) {
        // Wrong password: keep it queued until it's corrected.
        is HttpStatusException -> when {
            e.isAuthError -> Failure.UNREACHABLE
            e.status >= 500 || e.status == 429 -> Failure.SERVER
            else -> Failure.FINAL
        }
        is ServerApiException -> if (e.isAuthError) Failure.UNREACHABLE else Failure.FINAL
        is UnexpectedResponseException -> Failure.SERVER
        // Connection errors: no network to the server.
        is IOException -> Failure.UNREACHABLE
        else -> Failure.FINAL
    }

    private companion object {
        const val TAG = "SyncQueue"
        const val MAX_ATTEMPTS = 20
        const val MAX_UNREACHABLE_AGE_MS = 30L * 24 * 60 * 60 * 1000
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

/** Kicks off the queue if anything is still pending (e.g. after app start or sync). */
class SyncQueueStarter @Inject constructor(
    private val syncDao: SyncDao,
    private val scheduler: SyncScheduler,
) {
    suspend fun startIfPending() {
        if (syncDao.countActions() > 0) scheduler.resume()
    }
}

/** Enqueues an action and merges it with earlier ones for the same target. */
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
