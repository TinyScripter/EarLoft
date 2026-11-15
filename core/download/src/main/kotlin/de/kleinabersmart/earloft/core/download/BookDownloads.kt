package de.kleinabersmart.earloft.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.HeardDownload
import de.kleinabersmart.earloft.core.data.podcast.expiredHeardDownloads
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.spoken.automaticBookDownloads
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.FinishedDownloadRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Mobilfunk nur, wenn der Nutzer es erlaubt hat und er selbst lädt; die Automatik lädt immer nur im WLAN. */
internal fun bookDownloadNetwork(mobileAllowed: Boolean, automatic: Boolean): NetworkType =
    if (mobileAllowed && !automatic) NetworkType.CONNECTED else NetworkType.UNMETERED

/**
 * Downloads von Hörbüchern: von Hand, und mit den Schaltern unter Bücher → Downloads auch automatisch (die ersten aus
 * „Weiterhören“, der nächste Band einer Serie, bald ablaufende Bücher aus ARD Sounds; nur im WLAN, [automaticBookDownloads]). Beendete Bücher verlieren ihre
 * Dateien nach der eingestellten Frist ([SpokenSettings.bookDeleteFinishedDays], Standard nie), sonst bleiben sie, bis der
 * Nutzer sie löscht. Beim Abspielen ersetzen die Dateien den Stream (`BookPlaybackSource`). Netz: WLAN, mit dem eigenen
 * Schalter „Auch über Mobilfunk laden“ der Bücher ([SpokenSettings.bookMobileData]) jedes Netz.
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

    /** Das Buch im Player (aus [start]); seine Dateien bleiben beim Aufräumen beendeter Bücher. */
    private var loadedBookId: Flow<Long?> = flowOf(null)

    /** Die zuletzt vollständig eingereihte Auswahl der Automatik; erst eine andere wird wieder eingereiht. */
    private var handled: List<Long> = emptyList()

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

    /** Wie [EpisodeDownloads.deleteOrphans]: Dateien gelöschter Bücher, nur ohne laufende oder wartende Downloads. */
    suspend fun deleteOrphans(): Int {
        if (dao.countPendingBookDownloads() > 0) return 0
        return storage.deleteOrphans(dao.downloadedBookTracks().mapNotNull { it.filePath }) + storage.deleteParts()
    }

    /** Löscht die Dateien beendeter Bücher, deren Frist abgelaufen ist (täglich aus dem `HeardCleanupWorker`). @return Anzahl. */
    suspend fun removeExpiredFinished(): Int =
        removeExpired(dao.observeFinishedBookDownloads().first(), settings.bookDeleteFinishedDays.first(), loadedBookId.first())

    private suspend fun removeExpired(rows: List<FinishedDownloadRow>, days: Int, loaded: Long?): Int {
        // Wie bei Folgen zählt, wann die Dateien fertig wurden: Wer ein beendetes Buch neu lädt, behält es.
        val finished = rows.filter { it.bookId != loaded }.groupBy { it.bookId }.map { (bookId, files) ->
            HeardDownload(bookId, files.first().finishedAt, files.maxOf { row -> row.filePath?.let(::File)?.lastModified() ?: 0L })
        }
        return expiredHeardDownloads(finished, days, System.currentTimeMillis()).onEach { remove(it) }.size
    }

    /**
     * Einmal beim Start der App: wartende Downloads nach einem Prozessende fortsetzen, Netzeinstellung nachziehen,
     * Automatik und Aufräumen anhängen. [loadedBookId]: was im Player liegt.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, loadedBookId: Flow<Long?> = flowOf(null)) {
        if (!started.compareAndSet(false, true)) return
        this.loadedBookId = loadedBookId
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
        // Automatik, nur mit einem ihrer Schalter: bei jeder Änderung von Fortschritt, Büchern und WLAN, höchstens alle
        // [AUTOMATIC_SAMPLE_MS] (beim Hören ändert sich der Fortschritt alle paar Sekunden). Ohne WLAN wird nichts
        // eingereiht, im WLAN dann nachgeholt; ein Buch ohne Dateiliste (Server nicht erreichbar) beim nächsten Mal.
        scope.launch {
            combine(settings.bookAutoStarted, settings.bookAutoNextInSeries, settings.bookLoadExpiring, ::Triple)
                .distinctUntilChanged()
                .flatMapLatest { (started, next, expiring) ->
                    if (!started && !next && !expiring) return@flatMapLatest flowOf(emptyList())
                    // Ablaufende Bücher ändern keine Zeile, wenn ihre Frist unter die Schwelle rutscht: Die Uhr stößt es an.
                    combine(books.continueListening(), books.books(), unmeteredNetwork(), expiryCheck()) { continueListening, all, wifi, _ ->
                        if (!wifi) return@combine emptyList()
                        automaticBookDownloads(continueListening, all, started, next, System.currentTimeMillis(), expiring)
                    }
                }
                .sample(AUTOMATIC_SAMPLE_MS)
                .collect { bookIds ->
                    if (bookIds != handled && queueAutomatic(bookIds)) handled = bookIds
                }
        }
        scope.launch {
            combine(dao.observeFinishedBookDownloads(), settings.bookDeleteFinishedDays, loadedBookId, ::Triple)
                .collect { (finished, days, loaded) -> removeExpired(finished, days, loaded) }
        }
    }

    /** Meldet sich sofort und dann alle [EXPIRY_CHECK_MS] (nur solange die Automatik läuft). */
    private fun expiryCheck(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(EXPIRY_CHECK_MS)
        }
    }

    /** Ob das Standardnetz ohne Volumenbegrenzung ist (WLAN); meldet jeden Wechsel. */
    private fun unmeteredNetwork(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: run {
            trySend(false)
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(connectionState(mobileAllowed = false) == DownloadStart.STARTED)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** @return ob alle Bücher erledigt sind (sonst fehlte einem die Dateiliste vom Server). */
    private suspend fun queueAutomatic(bookIds: List<Long>): Boolean {
        var queued = 0
        var complete = true
        for (bookId in bookIds) {
            // Dateiliste vom Server, falls noch nicht geladen; ohne Verbindung beim nächsten Mal.
            if (books.timeline(bookId) == null) {
                complete = false
                continue
            }
            queued += dao.queueBookDownloadAutomatic(bookId)
        }
        if (queued > 0) {
            Log.i(TAG, "Automatik: $queued Dateien von ${bookIds.size} Büchern eingereiht")
            enqueueWorker(automatic = true)
        }
        return complete
    }

    /**
     * [automatic]: nur von der Automatik eingereiht, die im WLAN anstößt; der Auftrag bleibt dann auf WLAN beschränkt, auch
     * wenn Mobilfunk erlaubt ist (verlässt man das WLAN, würde er sonst mobil weiterladen).
     */
    private suspend fun enqueueWorker(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE, automatic: Boolean = false) {
        val network = bookDownloadNetwork(settings.bookMobileData.first(), automatic)
        // Ein angehängter Auftrag wartet auf den vorigen: Ein auf WLAN wartender Auftrag der Automatik hielte einen von Hand
        // gestarteten mobilen sonst auf. Der Auftrag arbeitet ohnehin die ganze Warteschlange ab.
        val chain = if (network == NetworkType.CONNECTED && policy == ExistingWorkPolicy.APPEND_OR_REPLACE && !workerRunning()) {
            ExistingWorkPolicy.REPLACE
        } else {
            policy
        }
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, chain, request)
    }

    private suspend fun workerRunning(): Boolean = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get().any { it.state == WorkInfo.State.RUNNING }
    }

    private companion object {
        const val WORK_NAME = "book-downloads"
        const val TAG = "BookDownloads"
        const val AUTOMATIC_SAMPLE_MS = 5_000L
        const val EXPIRY_CHECK_MS = 6 * 60 * 60 * 1000L
    }
}
