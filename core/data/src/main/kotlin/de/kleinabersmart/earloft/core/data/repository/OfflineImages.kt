package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.AuthorImages
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.bookAuthors
import de.kleinabersmart.earloft.core.model.isLocalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private val Context.offlineImagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "offline_images")

/**
 * Hält alle Bilder auch ohne Netz bereit (nur am Handy): Cover der Server-Alben, Bilder der Abos und Folgen, Cover der
 * Bücher, Autorenbilder und – wenn die Option an ist – Interpreten-Bilder. Bisher kamen sie erst beim Anzeigen und lagen im
 * Cache-Verzeichnis; offline fehlten so alle, die noch nie zu sehen waren.
 *
 * Ändert sich der Bestand (Alben, Interpreten, Bücher, Folgen, Abos oder deren Bilder, eingeschaltete Bereiche), lädt
 * [OfflineImagesWorker] im WLAN, was fehlt, und räumt Bilder weg, zu denen es nichts mehr gibt. Der Stand des letzten
 * vollständigen Laufs ist gemerkt: Ein Neustart des Prozesses ohne Änderung lädt nichts. Ein Lauf, in dem ein Server oder
 * Abo nicht antwortete, gilt nicht als vollständig (Review D7): Er wiederholt sich mit Backoff, danach beim nächsten Start.
 */
@Singleton
class OfflineImages @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val libraryDao: LibraryDao,
    private val bookDao: BookDao,
    private val podcastDao: PodcastDao,
    private val artwork: ArtworkRepository,
    private val bookCovers: BookCovers,
    private val authorImages: AuthorImages,
    private val artworkSettings: ArtworkSettings,
    private val spokenSettings: SpokenSettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // Der erste Stand nach dem Prozessstart lässt einen wartenden Lauf (Backoff) stehen; erst eine Änderung ersetzt ihn.
            var first = true
            combine(
                libraryDao.observeImageStamp(),
                bookDao.observeImageStamp(),
                spokenSettings.areas,
                artworkSettings.artistImages,
                artworkSettings.authorImages,
            ) { music, spoken, areas, artists, authors -> "$music|$spoken|${areas.sorted()}|$artists|$authors" }
                .distinctUntilChanged()
                // Ein Abgleich schreibt in vielen Schritten; geladen wird erst, wenn er durch ist.
                .debounce(DEBOUNCE_MS)
                .collect { stamp ->
                    if (stamp != doneStamp()) OfflineImagesWorker.enqueue(context, stamp, replace = !first)
                    first = false
                }
        }
    }

    /**
     * Lädt alle fehlenden Bilder der eingeschalteten Bereiche, neueste Folgen und Bücher zuerst, und räumt verwaiste weg.
     * Antwortet ein Server oder Abo mehrmals nicht, bleibt der Rest von ihm für den nächsten Lauf (statt jedes Mal den
     * Zeitüberschreitungen nachzuwarten). @return `false`, wenn deshalb etwas übersprungen wurde.
     */
    suspend fun prefetch(): Boolean {
        var complete = true
        val areas = spokenSettings.areas.first()
        if (SpokenMode.PODCASTS in areas) {
            val unreachable = Unreachable()
            podcastDao.feeds().filterNot { isLocalSource(it.feedId) }.forEach { feed ->
                unreachable.attempt(feed.feedId) { bookCovers.ensureFeedImageCached(feed.feedId) }
            }
            bookDao.listedCoverRows(BookKind.EPISODE)
                .filter { it.coverUrl != null && !isLocalSource(it.serverId) }
                .forEach { row -> unreachable.attempt(row.serverId) { bookCovers.ensureOwnCached(row.bookId) } }
            if (!unreachable.none()) complete = false
        }
        if (SpokenMode.BOOKS in areas) {
            val unreachable = Unreachable()
            bookDao.listedCoverRows(BookKind.BOOK)
                .filterNot { isLocalSource(it.serverId) }
                .forEach { row -> unreachable.attempt(row.serverId) { bookCovers.ensureOwnCached(row.bookId) } }
            // Autorenbilder kommen vom selben Server; antwortet er nicht, warteten sonst alle Autoren einzeln darauf.
            if (unreachable.none()) bookDao.listedAuthors().flatMap(::bookAuthors).distinct().forEach { authorImages.image(it) }
            if (!unreachable.none()) complete = false
        }
        if (SpokenMode.MUSIC in areas) {
            val unreachable = Unreachable()
            libraryDao.getAllAlbumIds().forEach { albumId ->
                // Cover vom Gerät liest die App ohnehin ohne Netz.
                val source = libraryDao.getCoverSource(albumId)?.takeIf { it.sourceType.isServer } ?: return@forEach
                unreachable.attempt(source.sourceId) { artwork.keepAlbumCoverOffline(albumId, source, ALBUM_COVER_PX) }
            }
            if (artworkSettings.artistImages.first()) libraryDao.getAllArtistIds().forEach { artwork.artistImage(it) }
            if (!unreachable.none()) complete = false
        }
        val removed = bookCovers.retainKnown() + artwork.retainAlbumCoverFiles()
        if (removed > 0) Log.i(TAG, "$removed Bilder ohne Eintrag entfernt")
        return complete
    }

    internal suspend fun markDone(stamp: String) {
        context.offlineImagesDataStore.edit { it[KEY_DONE_STAMP] = stamp }
    }

    private suspend fun doneStamp(): String? = context.offlineImagesDataStore.data.first()[KEY_DONE_STAMP]

    /** Zählt Fehlschläge je Server bzw. Abo; nach [MAX_FAILURES] in Folge wird er in diesem Lauf übersprungen. */
    private class Unreachable {
        private val failures = HashMap<String, Int>()

        suspend fun attempt(key: String, load: suspend () -> Boolean) {
            val count = failures[key] ?: 0
            if (count >= MAX_FAILURES) return
            failures[key] = if (load()) 0 else count + 1
        }

        fun none(): Boolean = failures.values.none { it >= MAX_FAILURES }
    }

    private companion object {
        const val TAG = "OfflineImages"
        const val DEBOUNCE_MS = 10_000L
        const val MAX_FAILURES = 3

        /** So groß wie die Cover der Downloads und das größte, das die Oberfläche anfragt: offline sieht es aus wie online. */
        const val ALBUM_COVER_PX = 1024
        val KEY_DONE_STAMP = stringPreferencesKey("done_stamp")
    }
}

/** Ein Lauf von [OfflineImages.prefetch]; nur im WLAN, wie die anderen Abrufe im Hintergrund. */
class OfflineImagesWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val offlineImages = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java).offlineImages()
        if (offlineImages.prefetch()) {
            inputData.getString(KEY_STAMP)?.let { offlineImages.markDone(it) }
            return Result.success()
        }
        // Unvollständig: nicht als erledigt merken, sonst käme der Rest erst mit dem nächsten geänderten Bestand.
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface WorkerEntryPoint {
        fun offlineImages(): OfflineImages
    }

    internal companion object {
        private const val WORK_NAME = "offline-images"
        private const val KEY_STAMP = "stamp"

        /** Läufe mit Backoff (5 min, verdoppelt) für einen unvollständigen Stand, zusammen gut 2,5 h. */
        private const val MAX_ATTEMPTS = 5

        /**
         * Mit [replace] ersetzt der neue Lauf einen laufenden bzw. wartenden: Er sieht den neuen Bestand, schon geladene Bilder
         * überspringt er.
         */
        fun enqueue(context: Context, stamp: String, replace: Boolean) {
            val request = OneTimeWorkRequestBuilder<OfflineImagesWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_STAMP to stamp))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
