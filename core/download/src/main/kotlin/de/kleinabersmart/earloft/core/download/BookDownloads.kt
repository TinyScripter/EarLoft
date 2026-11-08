package de.kleinabersmart.earloft.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.database.dao.BookDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads von Hörbüchern, nur von Hand (kein Aufräumen, keine Automatik): Die Dateien bleiben, bis der Nutzer sie
 * löscht. Beim Abspielen ersetzen sie den Stream (`BookPlaybackSource`). Netz: WLAN, mit dem eigenen Schalter
 * „Auch über Mobilfunk laden“ der Bücher ([SpokenSettings.bookMobileData]) jedes Netz.
 */
@Singleton
class BookDownloads @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val books: BookRepository,
    private val settings: SpokenSettings,
    private val storage: BookStorage,
) {
    private val started = AtomicBoolean(false)

    /**
     * Reiht alle Dateien des Buchs ein, die noch nicht geladen sind. Die Dateiliste kommt vom Server und muss dafür
     * erreichbar sein; ohne sie ist das Ergebnis [DownloadStart.OFFLINE].
     */
    suspend fun download(bookId: Long): DownloadStart {
        val mobileAllowed = settings.bookMobileData.first()
        if (books.timeline(bookId) == null) return DownloadStart.OFFLINE
        if (dao.queueBookDownload(bookId) > 0) enqueueWorker()
        return connectionState(mobileAllowed)
    }

    private fun connectionState(mobileAllowed: Boolean): DownloadStart {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        return when {
            capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> DownloadStart.OFFLINE
            !mobileAllowed && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> DownloadStart.WAITING_FOR_WIFI
            else -> DownloadStart.STARTED
        }
    }

    /** Löscht alle Dateien des Buchs (auch einen wartenden oder laufenden Download). */
    suspend fun remove(bookId: Long) {
        dao.markBookDownloadRemoved(bookId)
        storage.delete(bookId)
    }

    /** Einmal beim Start der App: wartende Downloads nach einem Prozessende fortsetzen, Netzeinstellung nachziehen. */
    /** Wie [EpisodeDownloads.deleteOrphans]: Dateien gelöschter Bücher, nur ohne laufende oder wartende Downloads. */
    suspend fun deleteOrphans(): Int {
        if (dao.countPendingBookDownloads() > 0) return 0
        return storage.deleteOrphans(dao.downloadedBookTracks().mapNotNull { it.filePath }) + storage.deleteParts()
    }

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Nach einem Prozessende stehen Einträge evtl. noch auf RUNNING/QUEUED. KEEP: Hat der Worker selbst den Prozess
            // gestartet, hängte sich sonst ein zweiter an ihn, der danach nichts mehr findet.
            if (dao.countPendingBookDownloads() > 0) enqueueWorker(ExistingWorkPolicy.KEEP)
        }
        // Die Einstellung „Mobilfunk“ gilt für wartende Downloads sofort: den Auftrag mit neuen Bedingungen ersetzen.
        scope.launch {
            settings.bookMobileData.distinctUntilChanged().drop(1).collect {
                if (dao.countPendingBookDownloads() > 0) enqueueWorker(ExistingWorkPolicy.REPLACE)
            }
        }
    }

    private suspend fun enqueueWorker(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val mobile = settings.bookMobileData.first()
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "book-downloads"
    }
}
