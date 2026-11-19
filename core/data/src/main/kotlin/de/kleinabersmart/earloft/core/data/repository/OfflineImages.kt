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
 * Keeps all images available without network too (phone only): covers of server albums, images of subscriptions and episodes, covers of
 * books, author images and – if the option is on – artist images. Previously they only came when displayed and lived in the
 * cache directory; offline, all those never seen before were missing.
 *
 * When the inventory changes (albums, artists, books, episodes, subscriptions or their images, enabled sections),
 * [OfflineImagesWorker] downloads what is missing over Wi-Fi and cleans up images that no longer belong to anything. The state of the last
 * complete run is remembered: a process restart without changes downloads nothing. A run in which a server or
 * subscription didn't respond doesn't count as complete (review D7): it is retried with backoff, then on the next start.
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
            // The first state after process start leaves a waiting run (backoff) in place; only a change replaces it.
            var first = true
            combine(
                libraryDao.observeImageStamp(),
                bookDao.observeImageStamp(),
                spokenSettings.areas,
                artworkSettings.artistImages,
                artworkSettings.authorImages,
            ) { music, spoken, areas, artists, authors -> "$music|$spoken|${areas.sorted()}|$artists|$authors" }
                .distinctUntilChanged()
                // A sync writes in many steps; downloading only starts once it is done.
                .debounce(DEBOUNCE_MS)
                .collect { stamp ->
                    if (stamp != doneStamp()) OfflineImagesWorker.enqueue(context, stamp, replace = !first)
                    first = false
                }
        }
    }

    /**
     * Downloads all missing images of the enabled sections, newest episodes and books first, and cleans up orphaned ones.
     * If a server or subscription repeatedly doesn't respond, the rest of it is left for the next run (instead of waiting out the
     * timeouts every time). @return `false` if something was skipped because of this.
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
                .forEach { row -> unreachable.attempt(row.serverId) { bookCovers.ensureOwnCached(row) } }
            if (!unreachable.none()) complete = false
        }
        if (SpokenMode.BOOKS in areas) {
            val unreachable = Unreachable()
            bookDao.listedCoverRows(BookKind.BOOK)
                .filterNot { isLocalSource(it.serverId) }
                .forEach { row -> unreachable.attempt(row.serverId) { bookCovers.ensureOwnCached(row) } }
            // Author images come from the same server; if it doesn't respond, all authors would otherwise wait for it individually.
            if (unreachable.none()) bookDao.listedAuthors().flatMap(::bookAuthors).distinct().forEach { authorImages.image(it) }
            if (!unreachable.none()) complete = false
        }
        if (SpokenMode.MUSIC in areas) {
            val unreachable = Unreachable()
            libraryDao.getAllAlbumIds().forEach { albumId ->
                // The app reads covers from the device without network anyway.
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

    /** Counts failures per server or subscription; after [MAX_FAILURES] in a row it is skipped for this run. */
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

        /** As large as the covers of the downloads and the largest the UI requests: offline looks like online. */
        const val ALBUM_COVER_PX = 1024
        val KEY_DONE_STAMP = stringPreferencesKey("done_stamp")
    }
}

/** A run of [OfflineImages.prefetch]; Wi-Fi only, like the other background fetches. */
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
        // Incomplete: don't mark as done, otherwise the rest would only come with the next inventory change.
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

        /** Runs with backoff (5 min, doubled) for an incomplete state, about 2.5 h in total. */
        private const val MAX_ATTEMPTS = 5

        /**
         * With [replace], the new run replaces a running or waiting one: it sees the new inventory and skips images
         * already downloaded.
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
