package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.flow.first
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvent
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvents
import de.kleinabersmart.earloft.core.data.refresh.RefreshKind
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.work.ensurePeriodicWork
import de.kleinabersmart.earloft.core.database.dao.BookDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält Hörbücher (und die Podcast-Dateien vom Gerät) aktuell: beim Öffnen der App (höchstens alle 10 min) die Dateien vom
 * Gerät und je Server ein Vollabgleich (> 6 h) bzw. der Fortschritt des Kontos, im Hintergrund per [BookRefreshWorker]
 * (nur WLAN), auf Anforderung alles. Der bloße Start des Prozesses gleicht nichts ab: Er kommt auch für Hintergrund-Jobs
 * und Nachrichten der Uhr.
 */
@Singleton
class SpokenRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val events: RefreshEvents,
    private val syncer: BookSyncer,
    private val progressSync: BookProgressSync,
    private val bookmarks: Bookmarks,
    private val servers: AudiobookshelfServers,
    private val dao: BookDao,
    private val localBooks: LocalBooks,
    private val localPodcasts: LocalPodcasts,
    private val ardBooks: ArdBooks,
    private val settings: SpokenSettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastQuick = AtomicLong(0)
    private val lastArd = AtomicLong(0)
    private val _refreshing = MutableStateFlow(false)

    /** `true`, solange ein Abgleich läuft, den der Nutzer angestoßen hat (für Pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Bücher vom Gerät: jedes Mal, wenn der Schalter wechselt (aus = entfernen). Den Stand beim Start gleicht
        // [refreshQuick] ab, sobald die App sichtbar wird.
        scope.launch {
            settings.useLocalBooks.distinctUntilChanged().drop(1).collect { enabled -> if (enabled) localBooks.sync() else localBooks.remove() }
        }
        scope.launch {
            settings.useLocalPodcasts.distinctUntilChanged().drop(1).collect { enabled -> if (enabled) localPodcasts.sync() else localPodcasts.remove() }
        }
        scope.launch { schedule() }
    }

    /** Hält die Bibliothek auch ohne geöffnete App aktuell (nur im WLAN), damit neue Bücher gemeldet werden können. */
    private suspend fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        // Geänderte Bedingungen gelten auch für einen schon geplanten Auftrag (der Takt bleibt); gleiche lassen ihn unberührt.
        WorkManager.getInstance(context).ensurePeriodicWork(
            WORK_NAME, BookRefreshWorker::class.java, BACKGROUND_INTERVAL_HOURS, TimeUnit.HOURS, constraints,
        ) {
            setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
        }
    }

    /**
     * Beim Öffnen der App: Dateien vom Gerät, ausstehenden Fortschritt senden, dann je Server ein Vollabgleich, wenn der
     * letzte länger als [FULL_SYNC_INTERVAL_MS] her ist, sonst nur den Fortschritt anderer Geräte übernehmen. Ohne [books]
     * bzw. [podcasts] (Bereich ausgeblendet) bleibt der jeweilige Teil aus.
     */
    fun refreshQuick(books: Boolean = true, podcasts: Boolean = true) {
        if (!books && !podcasts) return
        val now = System.currentTimeMillis()
        val last = lastQuick.get()
        if (now - last < QUICK_INTERVAL_MS || !lastQuick.compareAndSet(last, now)) return
        scope.launch {
            // Neue Dateien in „Audiobooks“; unveränderte Bücher kosten nur die MediaStore-Abfrage.
            if (books) localBooks.sync()
            if (podcasts) localPodcasts.sync()
            // Ausgeblendete Bücher (Allgemein → Bereiche): kein Kontakt zu Audiobookshelf.
            if (!books) return@launch
            progressSync.pushPending()
            bookmarks.pushPending()
            var fresh = 0
            servers.all().forEach { server ->
                if (now - (dao.server(server.serverId)?.lastFullSyncAt ?: 0) > FULL_SYNC_INTERVAL_MS) {
                    // Der Vollabgleich übernimmt den Fortschritt des Kontos mit.
                    fresh += syncer.syncSafely(server).newBooks.size
                    return@forEach
                }
                try {
                    bookmarks.applyRemote(server.serverId, progressSync.pullAll(server).bookmarks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Fortschritt von ${server.baseUrl} nicht abrufbar", e)
                }
            }
            // Die Bücher aus ARD Sounds gehören nicht zu einem Server: Sie werden auch ohne Audiobookshelf abgeglichen.
            val lastArdAt = lastArd.get()
            if (now - lastArdAt > FULL_SYNC_INTERVAL_MS && lastArd.compareAndSet(lastArdAt, now)) fresh += ardBooks.refresh().newBooks.size
            if (fresh > 0) events.emit(RefreshEvent.Finished(RefreshKind.BOOKS, fresh, failed = false))
        }
    }

    /** Auf Anforderung: meldet Start und Ende (mit Zahl der neuen Bücher) an die Oberfläche. */
    fun refreshAll() {
        scope.launch {
            _refreshing.value = true
            try {
                events.emit(RefreshEvent.Started(RefreshKind.BOOKS))
                val result = syncer.syncAll()
                events.emit(RefreshEvent.Finished(RefreshKind.BOOKS, result.newBooks.size, failed = !result.ok))
            } finally {
                _refreshing.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SpokenRefresher"
        const val WORK_NAME = "book-refresh"
        const val BACKGROUND_INTERVAL_HOURS = 12L
        const val FULL_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val QUICK_INTERVAL_MS = 10 * 60 * 1000L
    }
}

class BookRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java)
        // Ausgeblendete Bücher (Allgemein → Bereiche): kein Abgleich mit Audiobookshelf.
        if (SpokenMode.BOOKS !in entryPoint.spokenSettings().areas.first()) return Result.success()
        val servers = entryPoint.bookServers().all()
        val ardBooks = entryPoint.ardBooks()
        val ardOn = ardBooks.enabled.first()
        if (servers.isEmpty() && !ardOn) return Result.success()
        val results = servers.map { entryPoint.bookSyncer().syncSafely(it) }
        // Auch ohne Server: Bücher aus ARD Sounds (Ablauf, neue Bücher abonnierter Sendungen).
        val ard = ardBooks.refresh()
        val fresh = results.flatMap { it.newBooks } + ard.newBooks
        if (fresh.isNotEmpty()) entryPoint.notifier().show(NewContent.Books(fresh))
        // Wie bei Podcasts: Ein Server, der hier nicht erreichbar ist (nur im Heimnetz), glich sonst alle paar Minuten
        // alle Server neu ab; wiederholt wird nur, wenn keiner ging.
        val reached = results.any { it.ok } || (servers.isEmpty() && ard.ok)
        return if (!reached && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RefreshEntryPoint {
        fun bookSyncer(): BookSyncer
        fun bookServers(): AudiobookshelfServers
        fun ardBooks(): ArdBooks
        fun notifier(): NewContentNotifier
        fun spokenSettings(): SpokenSettings
    }
}
