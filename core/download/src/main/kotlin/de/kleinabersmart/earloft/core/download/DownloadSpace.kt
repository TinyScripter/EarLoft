package de.kleinabersmart.earloft.core.download

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What new downloads in [directory] may still use: free space plus cache Android can delete, minus the "storage low"
 * threshold at which it stops download jobs (`setRequiresStorageNotLow`). The plain free space was too optimistic: a
 * download that fit by it ran into that threshold and was stopped again and again (review H7).
 */
// The plain free space only if the system can't tell the allocatable space (review2 J100).
@SuppressLint("UsableSpace")
internal fun systemAllocatableBytes(context: Context, directory: File): Long {
    val manager = context.getSystemService(StorageManager::class.java) ?: return directory.usableSpace
    return try {
        manager.getAllocatableBytes(manager.getUuidForPath(directory))
    } catch (e: IOException) {
        Log.w("DownloadSpace", "Allocatable space of $directory unknown", e)
        directory.usableSpace
    }
}

/**
 * Bytes the running downloads still have to write. Parallel downloads each checked the free space only for themselves
 * (review H7); now each one claims what it still needs, and the next one only starts if it fits after them.
 */
internal object RunningDownloads {
    private val remaining = HashMap<String, Long>()

    /**
     * Claims [bytes] for [key] if they fit into [available] after the other running downloads, keeping [reserve] free.
     * @return `false` if not; nothing is claimed then.
     */
    @Synchronized
    fun claim(key: String, bytes: Long, available: Long, reserve: Long): Boolean {
        val others = remaining.entries.sumOf { (other, value) -> if (other == key) 0L else value }
        if (available - others < bytes + reserve) return false
        remaining[key] = bytes
        return true
    }

    /** What [key] still has to write, as the download progresses. */
    @Synchronized
    fun update(key: String, bytes: Long) {
        if (key in remaining) remaining[key] = bytes.coerceAtLeast(0)
    }

    @Synchronized
    fun release(key: String) {
        remaining -= key
    }
}

/**
 * Android stopped the job because the storage ran low (its "storage not low" constraint no longer holds). The job stays
 * enqueued and starts again by itself once there is room. Android names the reason only from API 31 on; before, such a stop
 * looks like any other and the partial file stays.
 */
internal val ListenableWorker.stoppedForLowStorage: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW

/** The three download queues of the phone. */
enum class DownloadKind { MUSIC, BOOKS, EPISODES }

/**
 * Whether downloads wait for storage (review H7): one did not fit, or Android stopped the job because the storage ran
 * low. They stay queued and start again by themselves ([SpaceRetryWorker], or the job's "storage not low" constraint);
 * the downloads page says why nothing happens meanwhile.
 */
@Singleton
class DownloadSpace @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("download_space", Context.MODE_PRIVATE)

    private val _isShort = MutableStateFlow(DownloadKind.entries.any { prefs.getBoolean(it.name, false) })
    val isShort: StateFlow<Boolean> = _isShort.asStateFlow()

    /** A download of [kind] did not fit, or the job was stopped for low storage. */
    fun short(kind: DownloadKind) = set(kind, true)

    /** A run of [kind] started or ended without running out of room. */
    fun enough(kind: DownloadKind) = set(kind, false)

    private fun set(kind: DownloadKind, short: Boolean) {
        if (prefs.getBoolean(kind.name, false) != short) prefs.edit { putBoolean(kind.name, short) }
        _isShort.value = DownloadKind.entries.any { prefs.getBoolean(it.name, false) }
    }

    /**
     * After a file did not fit: a new run of [kind] once the storage is not low and some time has passed. Android reports
     * freed space only through that constraint, and a file larger than what is free fits only after the user deleted something.
     */
    fun retryLater(kind: DownloadKind) {
        val request = OneTimeWorkRequestBuilder<SpaceRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setInitialDelay(RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(SpaceRetryWorker.KEY_KIND to kind.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("download-space-retry-${kind.name}", ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val RETRY_DELAY_MINUTES = 30L
    }
}

/** Starts the downloads of one queue again after they did not fit ([DownloadSpace.retryLater]). */
class SpaceRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SpaceRetryEntryPoint::class.java)
        when (inputData.getString(KEY_KIND)?.let { name -> DownloadKind.entries.firstOrNull { it.name == name } }) {
            DownloadKind.MUSIC -> entryPoint.spaceRetryMusicScheduler().enqueue()
            DownloadKind.BOOKS -> entryPoint.spaceRetryBookDownloads().continueOnWifi()
            DownloadKind.EPISODES -> entryPoint.spaceRetryEpisodeDownloads().resume()
            null -> Unit
        }
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface SpaceRetryEntryPoint {
        // Own names: the watch's entry points declare `scheduler()` with its own type (one component implements all getters).
        fun spaceRetryMusicScheduler(): DownloadScheduler
        fun spaceRetryBookDownloads(): BookDownloads
        fun spaceRetryEpisodeDownloads(): EpisodeDownloads
    }

    internal companion object {
        const val KEY_KIND = "kind"
    }
}
