package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.decodeArdParts
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.BackupRowCounts
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.dao.UserDataDao
import de.kleinabersmart.earloft.core.database.entity.UserArdBookEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeDownloadEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeStarEntity
import de.kleinabersmart.earloft.core.database.entity.UserCollectionFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.BookKind
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps `user.db` ([de.kleinabersmart.earloft.core.database.UserDatabase]) in sync with the main database (review K9).
 *
 * - **Restore:** On the first start of this installation (flag in `noBackupFilesDir`, which is never backed up)
 *   the backed-up rows go into the main database, if they are missing there. After switching devices this is the
 *   only way back; after an update `user.db` is empty and nothing happens.
 * - **Progress** is tied to books that only exist again after a scan or sync. It is therefore applied on every start
 *   and afterwards whenever new books appear, and only if it is newer than the existing one.
 *   As long as its book is missing, it stays in `user.db`; it goes away with its subscription or server.
 * - **Bookmarks** likewise; they are added if their book has none at the same position yet.
 * - **Saved episodes** likewise; an already saved one keeps its timestamp.
 * - **Books from ARD Sounds** are not brought back by any scan or server: [restoreArdBooks] fetches them from ARD Sounds, with the
 *   original time they were added; without a connection on the next start. After that their progress and bookmarks behave like the rest.
 * - **Music** ([MusicBackup]) likewise, as soon as its songs, albums and playlists are back: local playlists (created empty
 *   on restore), favorites of songs without a server, listening history and the selection for the watch.
 * - **Episode downloads** likewise, after progress: what was downloaded on the old device comes back, as long as it
 *   has not been played; nothing more – for a restored subscription the automation skips all other episodes after the
 *   first sync and only downloads episodes published afterwards. Before each decision it applies what already fits
 *   ([applyWaiting]); otherwise it would download after syncing a restored subscription, before the progress is there.
 * - **Mirroring:** After that, every change to the affected tables transfers only the differences, at most once per
 *   [MIRROR_INTERVAL_MS]; after a removal sooner ([REMOVAL_MIRROR_DELAY_MS]), so it cannot come back. The backup runs rarely anyway (cloud roughly daily); this way no write path can be forgotten.
 *   Only the parts whose tables changed are read (while listening to a book, only progress and statistics every 15 s
 *   instead of all user data); every [FULL_READ_INTERVAL_MS] everything again, for changes through unobserved
 *   tables (e.g. renamed albums in the keys).
 */
@Singleton
class UserDataBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: EarLoftDatabase,
    private val userDao: UserDataDao,
    private val sourceDao: SourceDao,
    private val bookDao: BookDao,
    private val podcastDao: PodcastDao,
    private val statsDao: StatsDao,
    private val music: MusicBackup,
    private val restoreNotice: RestoreNotice,
    /** Only needed once something has actually been restored. */
    private val registry: Lazy<DataSourceRegistry>,
    private val ardBooks: Lazy<ArdBooks>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /** Completed once the restore at startup has run, even if it failed. */
    private val restoreFinished = CompletableDeferred<Unit>()

    /** Whether it succeeded; otherwise `user.db` still holds a backup that mirroring must not overwrite. */
    @Volatile
    private var restoreSucceeded = false

    /** Completed once what already fits has been applied after startup (even if the restore failed). */
    private val firstApplied = CompletableDeferred<Unit>()

    /** Backed-up data still waiting for its book, song etc., along with the snapshot it came from; changes under [mutex]. */
    @Volatile
    private var waiting: Waiting? = null

    private class Waiting(val saved: UserDataSnapshot, val pending: Pending)

    private val _downloadsQueued = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Backed-up episode downloads are pending again; `EpisodeDownloads` then starts its job. */
    val downloadsQueued: Flow<Unit> = _downloadsQueued

    /** Mirroring and applying read and write the same rows. */
    private val mutex = Mutex()

    /** The snapshot last written to `user.db`; saves re-reading `user.db` on every sync. */
    private var mirrored: UserDataSnapshot? = null

    /** When all parts were last read (`elapsedRealtime`). */
    private var fullReadAt = 0L

    /** The newest event of the listening history in [mirrored]; if only that changed, only newer events are read. */
    private var mirroredPlayEventId = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                try {
                    restoreOnce()
                    restoreSucceeded = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No flag: it is retried on the next start. Mirroring still does not happen, so that a backup
                    // not yet restored is not overwritten by the empty state.
                    Log.e(TAG, "User data could not be restored", e)
                    return@launch
                } finally {
                    restoreFinished.complete(Unit)
                }
                val saved = try {
                    readUser()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Without the backup nothing can be held back from mirroring: as with a failed restore, no mirroring.
                    Log.e(TAG, "User data could not be read", e)
                    return@launch
                }
                applyFirst(saved)
            } finally {
                firstApplied.complete(Unit)
            }
            launch { restoreArdBooks() }
            launch { applyWhenLibraryChanges() }
            // Changes accumulate here, even while mirroring or waiting is in progress (`conflate`).
            val changed = ConcurrentHashMap.newKeySet<String>()
            database.invalidationTracker.createFlow(*MIRRORED_TABLES)
                .onEach { changed += it }
                .conflate()
                .collect {
                    val tables = changed.toSet().also { changed.removeAll(it) }
                    // Counted before mirroring: what is removed meanwhile is either in this mirror already or shrinks the count.
                    val counts = rowCountsOrNull()
                    try {
                        mutex.withLock { mirror(tables) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "User data could not be backed up", e)
                        mirrored = null
                    }
                    awaitNextMirror(counts)
                }
        }
    }

    /**
     * The pause until the next mirroring: [MIRROR_INTERVAL_MS], unless something was removed meanwhile (fewer rows than
     * [counts]); that goes after [REMOVAL_MIRROR_DELAY_MS]. Otherwise a removed star, bookmark, watch selection, ARD book or
     * an undone "mark as played" would come back from `user.db` if the process ends within the interval (review4 T20).
     * Mere progress and statistics keep the interval.
     */
    private suspend fun awaitNextMirror(counts: BackupRowCounts?) {
        withTimeoutOrNull(MIRROR_INTERVAL_MS) {
            delay(REMOVAL_MIRROR_DELAY_MS)
            counts ?: awaitCancellation()
            // Emits once right away: a removal during the mirroring or the delay counts as well.
            database.invalidationTracker.createFlow(*MIRRORED_TABLES).first { rowCountsOrNull()?.shrankSince(counts) == true }
        }
    }

    private suspend fun rowCountsOrNull(): BackupRowCounts? = try {
        database.backupDao().rowCounts()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Rows of the backup could not be counted", e)
        null
    }

    /**
     * Waits until backed-up servers and subscriptions are back in the main database (only noticeably slow on the first
     * start of an installation). Only then does the setup wizard decide whether there is content already.
     */
    suspend fun awaitRestore() = restoreFinished.await()

    /**
     * Applies what from the backup fits now. The download automation calls this before every decision: backed-up
     * progress and downloads should apply before it downloads the newest unplayed episodes – otherwise, after
     * syncing a restored subscription, it would download played episodes and those whose download was removed on the old device.
     */
    suspend fun applyWaiting() {
        // Without [start] (e.g. in tests) there is nothing to apply.
        if (!started.get()) return
        firstApplied.await()
        if (waiting == null) return
        mutex.withLock { waiting?.let { applyAndWait(it.saved, it.pending) } }
    }

    /**
     * Everything from [saved] as still to be applied; plus the subscriptions a restore created ([rememberRestoredFeeds]) that have
     * never been synced and have no episodes yet: after their first sync the automation only downloads there what was downloaded
     * on the old device. Only those: a subscription added here (OPML import offline) and not synced before a restart is no restored
     * one, and its automation works as usual (review4 T22). Without the listening history that is already here: otherwise every start
     * would look up each of its songs again.
     */
    internal suspend fun pendingOf(saved: UserDataSnapshot): Pending {
        val played = if (saved.playEvents.isEmpty()) emptySet() else music.playKeys()
        val restored = restoredFeeds()
        return Pending.of(saved).copy(
            // Progress without a backed-up server or subscription (earlier backups kept that of trial subscriptions) never finds
            // its book; it would be looked up again at every start (review4 T25).
            // A server or subscription known only here (import under the local identifier, not mirrored yet) still counts.
            progress = saved.progress.values.filterNot {
                saved.isOrphan(it.serverId) && bookDao.server(it.serverId) == null && podcastDao.feed(it.serverId)?.subscribed != true
            },
            playEvents = saved.playEvents.filterKeys { it !in played }.values.toList(),
            restoredFeeds = saved.podcasts.keys.filter { feedId ->
                feedId in restored && podcastDao.feed(feedId)?.lastRefreshAt == 0L && podcastDao.episodeCount(feedId) == 0
            },
        )
    }

    /**
     * A backed-up subscription that is a trial subscription here (preview open, episode in the queue) becomes a real one, with the
     * backed-up automatic download. Otherwise the backup would be matched to the trial by its address, nothing would be created, and the
     * progress would land on the trial, which the cleaner deletes later (review4 T23). @return how many.
     */
    private suspend fun subscribeTrials(saved: UserDataSnapshot): Int = saved.podcasts.values.count { podcast ->
        val local = podcastDao.feed(podcast.feedId) ?: podcastDao.feedByUrl(podcast.url)
        if (local == null || local.subscribed) return@count false
        podcastDao.subscribeTrial(local.feedId, podcast.autoDownload)
        true
    }

    /** Subscriptions created by a restore whose first sync is still outstanding; survives a restart. */
    private suspend fun restoredFeeds(): Set<String> = withContext(Dispatchers.IO) {
        File(context.noBackupFilesDir, RESTORED_FEEDS).takeIf { it.isFile }?.readLines()?.filterTo(HashSet()) { it.isNotBlank() }.orEmpty()
    }

    private suspend fun writeRestoredFeeds(feedIds: Collection<String>) = withContext(Dispatchers.IO) {
        val file = File(context.noBackupFilesDir, RESTORED_FEEDS)
        if (feedIds.isEmpty()) file.delete() else file.writeText(feedIds.joinToString("\n"))
    }

    /** [feedIds] were just created by a restore: they count as restored until their first sync ([pendingOf]). */
    private suspend fun rememberRestoredFeeds(feedIds: Collection<String>) {
        if (feedIds.isNotEmpty()) writeRestoredFeeds(restoredFeeds() + feedIds)
    }

    /**
     * The first apply after startup. If it fails, mirroring must still go on (e.g. after a full disk at startup, review2 J15),
     * but with all of [saved] as still waiting: otherwise the mirror would delete from `user.db` what isn't here yet (ARD books
     * not fetched yet, progress of books not synced yet), and [applyWhenLibraryChanges] would never apply it (review3 Q7).
     * [pending] only for tests.
     */
    internal suspend fun applyFirst(saved: UserDataSnapshot, pending: suspend () -> Pending = { pendingOf(saved) }) {
        try {
            mutex.withLock { applyAndWait(saved, pending()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "User data could not be applied", e)
            mutex.withLock { waiting = Waiting(saved, Pending.of(saved)) }
        }
    }

    /** Under [mutex]: applies and remembers the rest for later. */
    private suspend fun applyAndWait(saved: UserDataSnapshot, pending: Pending) {
        val rest = applySaved(saved, pending)
        waiting = if (rest.isEmpty) null else Waiting(saved, rest)
        // Synced (or removed) ones are done; the others stay restored across a restart.
        val restored = restoredFeeds()
        if (restored.isNotEmpty()) {
            writeRestoredFeeds(restored.filter { podcastDao.feed(it)?.lastRefreshAt == 0L && podcastDao.episodeCount(it) == 0 })
        }
    }

    /**
     * The snapshot for the full backup (`FullBackup`): `user.db` freshly mirrored, including what is still waiting for its book,
     * song etc.
     */
    suspend fun exportSnapshot(): UserDataSnapshot {
        // As with [import]: only after the first apply at startup. Before it, mirroring would delete from `user.db` what is
        // still waiting for its fetch (ARD books with their progress), and it would then live only in memory (review4 T24).
        if (started.get()) firstApplied.await() else restoreFinished.await()
        return mutex.withLock {
            if (restoreSucceeded) {
                mirror()
                readUser()
            } else {
                readMain(null, emptySet()).first.plusMissing(readUser())
            }
        }
    }

    /**
     * Restores the user data of a full backup. As with a device switch, data is only added: existing data stays,
     * progress and favorites apply if they are newer. Servers and subscriptions that already exist here under a different ID
     * (same address) are not created twice. Whatever has no book, song etc. yet goes into `user.db` and
     * follows as after a device switch, even across a restart. New servers then appear with "re-enter
     * password".
     */
    suspend fun import(saved: UserDataSnapshot): BackupImport {
        // Only after the first apply at startup: otherwise that would overwrite what is waiting here with its older state.
        if (started.get()) firstApplied.await() else restoreFinished.await()
        val (result, ids) = mutex.withLock {
            val trials = subscribeTrials(saved)
            val ids = localIdsOf(saved)
            val adjusted = withLocalIds(saved, ids)
            val musicIds = sourceDao.getServers().map { it.sourceId }.toSet()
            val spokenIds = bookDao.servers().map { it.serverId }.toSet()
            val result = BackupCounts(
                musicServers = adjusted.servers.keys.count { it !in musicIds },
                audiobookServers = adjusted.spokenServers.keys.count { it !in spokenIds },
                podcasts = adjusted.podcasts.keys.count { podcastDao.feed(it) == null } + trials,
                playlists = music.missingPlaylists(adjusted.playlists.keys),
            )
            restore(adjusted, mergeBookStats = true)
            val current = readUser()
            val merged = current.plusMissing(adjusted)
            val changes = merged.changesSince(current)
            if (!changes.isEmpty) userDao.apply(changes)
            // The remembered snapshot no longer matches `user.db`; re-read on the next mirroring.
            mirrored = null
            applyAndWait(merged, pendingOf(merged))
            result to ids
        }
        scope.launch { restoreArdBooks() }
        // `FullBackup` reports the note about missing passwords with its result.
        if (result.musicServers > 0) registry.get().reload()
        Log.i(TAG, "Backup restored: $result")
        return BackupImport(result, ids)
    }

    /**
     * IDs of servers and subscriptions from [saved] that exist here under a different ID: backed-up → local. Music servers by
     * type, address and account, Audiobookshelf by address, subscriptions by feed URL.
     */
    private suspend fun localIdsOf(saved: UserDataSnapshot): Map<String, String> {
        val music = sourceDao.getServers()
        val musicIds = saved.servers.values.mapNotNull { server ->
            music.firstOrNull { it.sourceId != server.sourceId && it.type == server.type && sameUrl(it.baseUrl, server.baseUrl) && it.username == server.username }
                ?.let { server.sourceId to it.sourceId }
        }
        val spoken = bookDao.servers()
        val spokenIds = saved.spokenServers.values.mapNotNull { server ->
            // By address: since multiple libraries, `libraryId` is only the first one shown and changes with selection and
            // order in Audiobookshelf. With several entries for the same address (old backups), the one with the same ID first.
            val local = spoken.firstOrNull { it.serverId == server.serverId }
                ?: spoken.filter { sameUrl(it.baseUrl, server.baseUrl) }.let { same -> same.firstOrNull { it.libraryId == server.libraryId } ?: same.firstOrNull() }
            local?.serverId?.takeIf { it != server.serverId }?.let { server.serverId to it }
        }
        val feedIds = saved.podcasts.values.mapNotNull { podcast ->
            (podcastDao.feed(podcast.feedId) ?: podcastDao.feedByUrl(podcast.url))?.feedId?.takeIf { it != podcast.feedId }?.let { podcast.feedId to it }
        }
        return (musicIds + spokenIds + feedIds).toMap()
    }

    /**
     * Removes from [saved] the servers and subscriptions that exist here under a different ID, and attaches their progress,
     * bookmarks, downloads, book statistics (grouped by server) and the watch's server playlists to the local ID.
     */
    private fun withLocalIds(saved: UserDataSnapshot, ids: Map<String, String>): UserDataSnapshot {
        if (ids.isEmpty()) return saved
        return saved.copy(
            listeningStats = saved.listeningStats.values
                .map { if (it.kind == BookKind.BOOK) it.copy(groupKey = ids[it.groupKey] ?: it.groupKey) else it }
                .groupBy { StatKey(it.source, it.groupKey, it.itemKey) }
                .mapValues { (_, rows) -> rows.maxBy { it.listenedMs } },
            listeningDays = saved.listeningDays.values
                .map { if (it.kind == BookKind.BOOK) it.copy(groupKey = ids[it.groupKey] ?: it.groupKey) else it }
                .groupBy { DayKey(it.day, it.kind.name, it.groupKey) }
                .mapValues { (_, rows) -> rows.maxBy { it.listenedMs } },
            wearSelections = saved.wearSelections.values.map { it.withLocalSourceIds(ids) }.associateBy { it.key },
            servers = saved.servers.filterKeys { it !in ids },
            spokenServers = saved.spokenServers.filterKeys { it !in ids },
            podcasts = saved.podcasts.filterKeys { it !in ids },
            progress = saved.progress.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .groupBy { BookKey(it.serverId, it.remoteId) }
                .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } },
            episodeDownloads = saved.episodeDownloads.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .associateBy { BookKey(it.serverId, it.remoteId) },
            bookmarks = saved.bookmarks.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .groupBy { it.key }
                .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } },
            episodeStars = saved.episodeStars.values
                .map { it.copy(serverId = ids[it.serverId] ?: it.serverId) }
                .associateBy { BookKey(it.serverId, it.remoteId) },
        )
    }

    private fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)

    /** The list of servers without a password is built on load; the settings should show "re-enter password". */
    private suspend fun serversRestored(music: Boolean, audiobooks: Boolean) {
        if (music) registry.get().reload()
        if (music || audiobooks) restoreNotice.serversRestored()
    }

    /**
     * Restores once per installation, and again whenever the main database was created after the last restore (e.g. Room
     * recreated it empty after corruption): otherwise mirroring would take the empty database for the wanted state and empty
     * `user.db` as well.
     */
    private suspend fun restoreOnce() {
        // Opening creates a missing main database now, so that its marker is older than the one set after restoring.
        withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
        val restoredBefore = withContext(Dispatchers.IO) { File(context.noBackupFilesDir, RESTORED_MARKER).exists() }
        if (restoredBefore && !mainRecreatedSinceRestore()) return
        if (restoredBefore) Log.w(TAG, "Main database was created after the last restore, restoring again")
        restoreFromBackup(readUser())
    }

    /** Creates what is missing from [saved] in the main database and sets the restore marker to now. */
    private suspend fun restoreFromBackup(saved: UserDataSnapshot) {
        if (!saved.isEmpty) {
            val musicIds = sourceDao.getServers().mapTo(HashSet()) { it.sourceId }
            val spokenIds = bookDao.servers().mapTo(HashSet()) { it.serverId }
            restore(saved)
            serversRestored(saved.servers.keys.any { it !in musicIds }, saved.spokenServers.keys.any { it !in spokenIds })
            Log.i(
                TAG,
                "User data restored: ${saved.servers.size} music servers, ${saved.spokenServers.size} ABS servers, " +
                    "${saved.podcasts.size} podcasts, ${saved.listeningStats.size} statistics entries, " +
                    "${saved.progress.size} progress entries, ${saved.bookmarks.size} bookmarks, ${saved.playlists.size} playlists, ${saved.favorites.size} favorites, " +
                    "${saved.playEvents.size} listening history entries, ${saved.wearSelections.size} watch selections, " +
                    "${saved.episodeDownloads.size} episode downloads, ${saved.episodeStars.size} saved episodes " +
                    "(follow with their books and songs)",
            )
        }
        withContext(Dispatchers.IO) {
            // `createNewFile` keeps the time of an existing file; a later restore must be newer than the database it restored into.
            File(context.noBackupFilesDir, RESTORED_MARKER).apply { createNewFile(); setLastModified(System.currentTimeMillis()) }
        }
    }

    /** Whether the main database was created after the last restore (both markers live in `noBackupFilesDir`). */
    private suspend fun mainRecreatedSinceRestore(): Boolean = withContext(Dispatchers.IO) {
        val restored = File(context.noBackupFilesDir, RESTORED_MARKER)
        val created = File(context.noBackupFilesDir, EarLoftDatabase.CREATED_MARKER)
        restored.exists() && created.exists() && created.lastModified() > restored.lastModified()
    }

    /**
     * Under [mutex], instead of mirroring: brings the backup back into the main database, which has lost it (see [mirror]).
     * Afterwards the next mirroring compares against `user.db` again.
     */
    private suspend fun restoreInsteadOfMirroring() {
        val saved = readUser()
        restoreFromBackup(saved)
        applyAndWait(saved, pendingOf(saved))
        mirrored = null
        scope.launch { restoreArdBooks() }
    }

    /**
     * Creates what is missing in the main database; existing data stays unchanged. With [mergeBookStats] (importing a file), the
     * book statistics that exist here as well keep the larger time instead.
     */
    internal suspend fun restore(saved: UserDataSnapshot, mergeBookStats: Boolean = false) {
        subscribeTrials(saved)
        val newFeeds = saved.podcasts.values.filter { podcastDao.feed(it.feedId) == null && podcastDao.feedByUrl(it.url) == null }
        database.withTransaction {
            saved.servers.values.forEach { sourceDao.insertIfAbsent(it.toMain()) }
            saved.spokenServers.values.forEach { if (bookDao.server(it.serverId) == null) bookDao.upsertServer(it.toMain()) }
            saved.podcasts.values.forEach {
                if (podcastDao.feed(it.feedId) == null && podcastDao.feedByUrl(it.url) == null) podcastDao.upsertFeed(it.toMain())
            }
            val stats = saved.listeningStats.values.map { it.toMain() }
            val days = saved.listeningDays.values.map { it.toMain() }
            if (mergeBookStats) {
                // Book statistics of a foreign device are attached to the local server and may overlap with local ones (review3 Q15).
                val (books, others) = stats.partition { it.kind == BookKind.BOOK }
                val (bookDays, otherDays) = days.partition { it.kind == BookKind.BOOK }
                statsDao.insertAllIfAbsent(others)
                statsDao.insertDaysIfAbsent(otherDays)
                statsDao.mergeStats(books)
                statsDao.mergeDays(bookDays)
            } else {
                statsDao.insertAllIfAbsent(stats)
                statsDao.insertDaysIfAbsent(days)
            }
            music.restorePlaylists(saved.playlists.values)
        }
        rememberRestoredFeeds(newFeeds.map { it.feedId })
    }

    /**
     * Transfers backed-up progress into the main database if its book exists there and it is newer than the
     * existing one. Returns the rest whose book is (still) missing.
     */
    internal suspend fun applyProgress(saved: Collection<UserBookProgressEntity>): List<UserBookProgressEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = mutableListOf<UserBookProgressEntity>()
        var applied = 0
        database.withTransaction {
            saved.groupBy { it.serverId }.forEach { (serverId, rows) ->
                val bookIds = rows.chunked(MAX_ARGS)
                    .flatMap { chunk -> bookDao.bookRefs(serverId, chunk.map { it.remoteId }) }
                    .associate { it.remoteId to it.bookId }
                val current = bookIds.values.chunked(MAX_ARGS).flatMap { bookDao.progressOf(it) }.associateBy { it.bookId }
                rows.forEach { row ->
                    val bookId = bookIds[row.remoteId]
                    when {
                        bookId == null -> unresolved += row
                        (current[bookId]?.updatedAt ?: Long.MIN_VALUE) < row.updatedAt -> {
                            bookDao.upsertProgress(row.toMain(bookId))
                            applied++
                        }
                    }
                }
            }
        }
        if (applied > 0) Log.i(TAG, "Progress restored: $applied, still without book: ${unresolved.size}")
        return unresolved
    }

    /**
     * Transfers backed-up bookmarks into the main database if their book exists there and has none at the same position
     * (not even a deleted one whose report is pending). If the existing one has no note, it gets the backed-up one:
     * Audiobookshelf has no notes, the sync brings its bookmarks back without them. Returns the rest whose
     * book is (still) missing.
     */
    internal suspend fun applyBookmarks(saved: Collection<UserBookmarkEntity>): List<UserBookmarkEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = mutableListOf<UserBookmarkEntity>()
        var applied = 0
        database.withTransaction {
            val abs = bookDao.servers().map { it.serverId }.toSet()
            saved.groupBy { it.serverId }.forEach { (serverId, rows) ->
                val bookIds = rows.map { it.remoteId }.distinct().chunked(MAX_ARGS)
                    .flatMap { chunk -> bookDao.bookRefs(serverId, chunk) }
                    .associate { it.remoteId to it.bookId }
                val current = bookIds.values.chunked(MAX_ARGS).flatMap { bookDao.bookmarksOf(it) }
                    .associateBy { it.bookId to it.positionMs }
                rows.forEach { row ->
                    val bookId = bookIds[row.remoteId]
                    val existing = bookId?.let { current[it to row.positionMs] }
                    when {
                        bookId == null -> unresolved += row
                        existing == null -> {
                            // For Audiobookshelf books, without a note only what the server doesn't have (in this form) is backed up:
                            // to be reported again. With a note it can also be one it already knows.
                            val dirty = serverId in abs && (row.remoteTime == null || row.note.isEmpty())
                            bookDao.insertBookmark(row.toMain(bookId, dirty))
                            applied++
                        }
                        !existing.deleted && existing.note.isEmpty() && row.note.isNotEmpty() -> {
                            bookDao.updateBookmark(existing.copy(note = row.note))
                            applied++
                        }
                    }
                }
            }
        }
        if (applied > 0) Log.i(TAG, "Bookmarks restored: $applied, still without book: ${unresolved.size}")
        return unresolved
    }

    /**
     * Transfers the backed-up download state to episodes that exist here and have never been downloaded here:
     * the automation skips removed and failed ones, downloaded ones come back as long as they have not been played to the end.
     * Returns the rest whose episode is (still) missing.
     */
    internal suspend fun applyEpisodeDownloads(saved: Collection<UserEpisodeDownloadEntity>): List<UserEpisodeDownloadEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = mutableListOf<UserEpisodeDownloadEntity>()
        var excluded = 0
        var queued = 0
        database.withTransaction {
            saved.groupBy { it.serverId }.forEach { (serverId, rows) ->
                val bookIds = rows.chunked(MAX_ARGS)
                    .flatMap { chunk -> bookDao.bookRefs(serverId, chunk.map { it.remoteId }) }
                    .associate { it.remoteId to it.bookId }
                val finished = bookIds.values.chunked(MAX_ARGS).flatMap { bookDao.progressOf(it) }.filter { it.finished }.map { it.bookId }.toSet()
                val found = rows.mapNotNull { row ->
                    val bookId = bookIds[row.remoteId]
                    if (bookId == null) unresolved += row
                    bookId?.let { row to it }
                }
                val (wanted, notWanted) = found.partition { (row, _) -> row.wanted }
                notWanted.map { it.second }.chunked(MAX_ARGS).forEach { excluded += podcastDao.excludeFromAutomatic(it) }
                wanted.map { it.second }.filter { it !in finished }.chunked(MAX_ARGS).forEach { queued += podcastDao.queueNeverDownloaded(it) }
            }
        }
        if (excluded > 0 || queued > 0) Log.i(TAG, "Downloads restored: $queued queued, $excluded excluded, still without episode: ${unresolved.size}")
        if (queued > 0) _downloadsQueued.tryEmit(Unit)
        return unresolved
    }

    /** Marks the backed-up saved episodes that exist here; returns the rest whose episode is (still) missing. */
    internal suspend fun applyEpisodeStars(saved: Collection<UserEpisodeStarEntity>): List<UserEpisodeStarEntity> {
        if (saved.isEmpty()) return emptyList()
        val unresolved = database.withTransaction {
            saved.filter { podcastDao.restoreStar(it.serverId, it.remoteId, it.starredAt) == 0 }
        }
        if (unresolved.size < saved.size) Log.i(TAG, "Saved episodes restored: ${saved.size - unresolved.size}, still without episode: ${unresolved.size}")
        return unresolved
    }

    /** Returns the backed-up books from ARD Sounds that don't exist here (yet); [restoreArdBooks] fetches them. */
    internal suspend fun applyArdBooks(saved: Collection<UserArdBookEntity>): List<UserArdBookEntity> {
        if (saved.isEmpty()) return emptyList()
        val here = bookDao.keyedBooksOf(ARD_BOOK_SERVER_ID).mapTo(HashSet()) { it.remoteId }
        return saved.filter { it.remoteId !in here }
    }

    /** Applies from [saved] what is listed in [pending]; returns what has no book, song etc. (yet). */
    internal suspend fun applySaved(saved: UserDataSnapshot, pending: Pending): Pending {
        val progress = applyProgress(pending.progress)
        val bookmarks = applyBookmarks(pending.bookmarks)
        val episodeStars = applyEpisodeStars(pending.episodeStars)
        val ardBooks = applyArdBooks(pending.ardBooks)
        // After progress: played episodes don't come back.
        val episodeDownloads = applyEpisodeDownloads(pending.episodeDownloads)
        // Afterwards: for restored subscriptions the automation downloads no more than was downloaded on the old device.
        val restoredFeeds = pending.restoredFeeds.filter { feedId ->
            val arrived = podcastDao.episodeCount(feedId) > 0
            if (arrived) podcastDao.excludeAllFromAutomatic(feedId)
            !arrived
        }
        val rest = database.withTransaction {
            Pending(
                progress = progress,
                bookmarks = bookmarks,
                episodeDownloads = episodeDownloads,
                episodeStars = episodeStars,
                ardBooks = ardBooks,
                restoredFeeds = restoredFeeds,
                playlists = music.applyPlaylists(saved, pending.playlists),
                favorites = music.applyFavorites(pending.favorites),
                collectionFavorites = music.applyCollectionFavorites(pending.collectionFavorites),
                playEvents = music.applyPlayEvents(pending.playEvents),
                wearSelections = music.applyWearSelections(pending.wearSelections),
            )
        }
        val found = listOf(
            "favorites" to pending.favorites.size - rest.favorites.size,
            "albums/artists" to pending.collectionFavorites.size - rest.collectionFavorites.size,
            "listening history" to pending.playEvents.size - rest.playEvents.size,
            "watch" to pending.wearSelections.size - rest.wearSelections.size,
        ).filter { it.second > 0 }
        if (found.isNotEmpty() || rest.playlists != pending.playlists) {
            Log.i(
                TAG,
                "Music restored: ${found.joinToString { "${it.first} ${it.second}" }}; still without song: " +
                    "${rest.playlists.size} playlists, ${rest.favorites.size} favorites, " +
                    "${rest.collectionFavorites.size} albums/artists, ${rest.playEvents.size} listening history, ${rest.wearSelections.size} watch",
            )
        }
        return rest
    }

    /**
     * Fetches the backed-up books from ARD Sounds that don't exist here yet. Afterwards [applyWhenLibraryChanges] applies their progress.
     * Without a connection, twice more later, then on the next start; one that ARD Sounds no longer lists stays backed up.
     * Only when the source's switch is on (default: off): otherwise every start would query ARD Sounds although the user never
     * enabled the source. While it is off, the restore waits until it is enabled.
     */
    internal suspend fun restoreArdBooks(pendingRows: () -> Collection<UserArdBookEntity> = { waiting?.pending?.ardBooks.orEmpty() }) {
        if (pendingRows().isEmpty()) return
        ardBooks.get().enabled.first { it }
        var wait = ARD_RETRY_MS
        repeat(ARD_ATTEMPTS) { attempt ->
            val missing = pendingRows()
            if (missing.isEmpty()) return
            if (attempt > 0) {
                delay(wait)
                wait *= 10
            }
            if (fetchArdBooks(missing)) return
        }
    }

    /** Fetches the books [rows] from ARD Sounds; `false` if there was no connection (the rest then stays unfetched). */
    internal suspend fun fetchArdBooks(rows: Collection<UserArdBookEntity>): Boolean {
        for (row in rows) {
            try {
                ardBooks.get().restore(row.remoteId, row.addedAt, decodeArdParts(row.parts))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Book from ARD Sounds not restored: ${row.remoteId}", e)
                return false
            } catch (e: Exception) {
                Log.e(TAG, "Book from ARD Sounds not restored: ${row.remoteId}", e)
            }
        }
        return true
    }

    /** Gradually applies what is waiting as soon as a scan or sync creates books, songs and playlists. */
    private suspend fun applyWhenLibraryChanges() {
        database.invalidationTracker.createFlow("books", "songs", "playlists")
            .conflate()
            .collect {
                if (waiting == null) return@collect
                try {
                    mutex.withLock { waiting?.let { applyAndWait(it.saved, it.pending) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "User data could not be restored", e)
                }
                delay(APPLY_INTERVAL_MS)
            }
    }

    /**
     * Forgets backed-up progress and bookmarks of the episodes [remoteIds] of [serverId] whose ID has
     * changed (switch source, concept 12.10): mirroring only deletes what still exists in the main database, and
     * the new IDs are added on their own.
     */
    suspend fun forgetProgress(serverId: String, remoteIds: Collection<String>) = mutex.withLock {
        remoteIds.chunked(MAX_ARGS).forEach {
            userDao.deleteProgressKeys(serverId, it)
            userDao.deleteBookmarkKeys(serverId, it)
            userDao.deleteEpisodeStarKeys(serverId, it)
        }
        // The remembered snapshot no longer matches `user.db`; re-read on the next mirroring.
        mirrored = null
    }

    /**
     * [changedTables]: re-read only their parts; `null` = everything.
     *
     * Restores instead of mirroring if the main database lost what the backup holds: it was created after the last restore
     * (while the app runs, e.g. after corruption), or, compared against `user.db` for the first time in this process, it
     * has no server, subscription or statistics at all while the backup has some. Removing everything within a session
     * is mirrored as usual.
     */
    internal suspend fun mirror(changedTables: Set<String>? = null) {
        if (mainRecreatedSinceRestore()) {
            Log.w(TAG, "Main database was created after the last restore, restoring instead of mirroring")
            restoreInsteadOfMirroring()
            return
        }
        val previous = mirrored ?: readUser()
        val now = SystemClock.elapsedRealtime()
        val base = mirrored.takeIf { changedTables != null && now - fullReadAt < FULL_READ_INTERVAL_MS }
        if (base == null) fullReadAt = now
        val (main, playEventId) = readMain(base, changedTables.orEmpty(), mirroredPlayEventId)
        val current = main.keepingUnchangedPlaylists(previous)
        if (mirrored == null && previous.hasServersFeedsOrStats && !current.hasServersFeedsOrStats) {
            Log.w(TAG, "Main database has no servers, subscriptions or statistics, the backup has; restoring instead of mirroring")
            restoreInsteadOfMirroring()
            return
        }
        // If the book or song is missing, the entry has not been applied yet (or the episode is no longer in the feed, the
        // file is gone): keep it. Only what exists here and was removed here is deleted.
        // Whatever is still to be applied stays as well, even if its book is already there.
        val stillWaiting = waiting?.pending
        val waitingProgress = stillWaiting?.progress.orEmpty().map { BookKey(it.serverId, it.remoteId) }.toSet()
        val waitingDownloads = stillWaiting?.episodeDownloads.orEmpty().map { BookKey(it.serverId, it.remoteId) }.toSet()
        val waitingBookmarks = stillWaiting?.bookmarks.orEmpty().map { it.key }.toSet()
        val waitingStars = stillWaiting?.episodeStars.orEmpty().map { BookKey(it.serverId, it.remoteId) }.toSet()
        val waitingArdBooks = stillWaiting?.ardBooks.orEmpty().map { it.remoteId }.toSet()
        val changes = current.changesSince(previous).let { changes ->
            // A book from ARD Sounds that has not been fetched back yet stays backed up, otherwise it would be dropped before fetching.
            val ardBookDeletes = changes.ardBooks.deletes.filter { it.remoteId !in waitingArdBooks }
            // Removing a book from ARD Sounds takes its progress and bookmarks along (review2 Z10): there is no server whose
            // removal would, and otherwise they would apply again if the book is added months later.
            val removedArd = ardBookDeletes.mapTo(HashSet()) { it.remoteId }
            fun removedWithArdBook(serverId: String, remoteId: String) = serverId == ARD_BOOK_SERVER_ID && remoteId in removedArd
            val progressDeletes = changes.progress.deletes.filter {
                removedWithArdBook(it.serverId, it.remoteId) ||
                    BookKey(it.serverId, it.remoteId) !in waitingProgress &&
                    (bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty() || current.isOrphan(it.serverId))
            }
            val bookmarkDeletes = changes.bookmarks.deletes.filter {
                removedWithArdBook(it.serverId, it.remoteId) ||
                    it.key !in waitingBookmarks && bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty()
            }
            val downloadDeletes = changes.episodeDownloads.deletes.filter {
                BookKey(it.serverId, it.remoteId) !in waitingDownloads && bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty()
            }
            val starDeletes = changes.episodeStars.deletes.filter {
                BookKey(it.serverId, it.remoteId) !in waitingStars && bookDao.bookRefs(it.serverId, listOf(it.remoteId)).isNotEmpty()
            }
            val songs = music.resolve((changes.favorites.deletes.map { it.song } + changes.playEvents.deletes.map { it.song }).toSet())
            // Rows without a year that the rows with a year here have replaced (review3 Q13): they match no edition, so the check
            // below would keep them for good.
            val replaced = AlbumsWithYear(current)
            changes.copy(
                progress = changes.progress.copy(deletes = progressDeletes),
                bookmarks = changes.bookmarks.copy(deletes = bookmarkDeletes),
                episodeDownloads = changes.episodeDownloads.copy(deletes = downloadDeletes),
                episodeStars = changes.episodeStars.copy(deletes = starDeletes),
                ardBooks = changes.ardBooks.copy(deletes = ardBookDeletes),
                favorites = changes.favorites.copy(deletes = changes.favorites.deletes.filter { it.song in songs || replaced.supersedes(it) }),
                collectionFavorites = changes.collectionFavorites.copy(
                    deletes = changes.collectionFavorites.deletes.filter { music.collectionState(it) != null || replaced.supersedes(it) },
                ),
                playEvents = changes.playEvents.copy(deletes = changes.playEvents.deletes.filter { it.song in songs || replaced.supersedes(it) }),
                wearSelections = changes.wearSelections.copy(deletes = changes.wearSelections.deletes.filter { music.wearTarget(it) != null || replaced.supersedes(it) }),
            )
        }
        if (!changes.isEmpty) userDao.apply(changes)
        mirrored = current
        mirroredPlayEventId = playEventId
    }

    /**
     * Re-reads the parts whose tables are in [changed] and takes the others from [base]; without [base], everything. Along with it
     * the newest event of the listening history read; if only that history changed, only events after [basePlayEventId] are read.
     */
    private suspend fun readMain(base: UserDataSnapshot?, changed: Set<String>, basePlayEventId: Long = 0): Pair<UserDataSnapshot, Long> {
        fun read(vararg tables: String) = base == null || tables.any { it in changed }
        val servers = read("sources")
        val spokenServers = read("spoken_servers")
        val feeds = read("podcast_feeds")
        val stats = read("listening_stats")
        val days = read("listening_days")
        // Which progress is backed up also depends on the Audiobookshelf servers.
        val progress = read("book_progress", "spoken_servers")
        val bookmarks = read("bookmarks", "spoken_servers")
        // Which downloads are backed up depends on the backed-up subscriptions.
        val downloads = read("book_tracks", "podcast_feeds")
        val stars = read("books")
        // The parts of the books from ARD Sounds change on sync.
        val ards = read("books", "book_tracks")
        // While music plays, only the listening history grows: then only its new events are read, not all of it (J18 in review2).
        val newPlaysOnly = base != null && MUSIC_TABLES.filter { it in changed } == listOf("play_events")
        val musicData = if (read(*MUSIC_TABLES) && !newPlaysOnly) music.read() else null
        val newPlays = if (newPlaysOnly) music.playEventsAfter(basePlayEventId) else null
        val newestRead = (musicData?.playEvents ?: newPlays).orEmpty().maxOfOrNull { it.eventId } ?: 0
        // A full read is the whole history: its newest event is the new start, even if lower than [basePlayEventId] (the main
        // database was created anew meanwhile, its IDs begin again; review3 Q16). Otherwise new events would only be seen at the
        // next full read.
        val playEventId = if (musicData != null) newestRead else maxOf(basePlayEventId, newestRead)
        val fresh = UserDataSnapshot.ofMain(
            sources = if (servers) sourceDao.getServers() else emptyList(),
            spokenServers = if (spokenServers || progress || bookmarks) bookDao.servers() else emptyList(),
            feeds = if (feeds || downloads) podcastDao.keptFeeds() else emptyList(),
            listeningStats = if (stats) statsDao.all() else emptyList(),
            listeningDays = if (days) statsDao.allDays() else emptyList(),
            progress = if (progress) bookDao.keyedProgressToBackUp() else emptyList(),
            playlists = musicData?.playlists.orEmpty(),
            playlistEntries = musicData?.playlistEntries.orEmpty(),
            favorites = musicData?.favorites.orEmpty(),
            playEvents = musicData?.playEvents.orEmpty(),
            wearSelections = musicData?.wearSelections.orEmpty(),
            episodeDownloads = if (downloads) podcastDao.keyedEpisodeDownloads() else emptyList(),
            collectionFavorites = musicData?.collectionFavorites.orEmpty(),
            bookmarks = if (bookmarks) bookDao.keyedBookmarks() else emptyList(),
            episodeStars = if (stars) podcastDao.keyedStarredEpisodes() else emptyList(),
            ardBooks = if (ards) bookDao.keyedBooksOf(ARD_BOOK_SERVER_ID) else emptyList(),
            ardParts = if (ards) bookDao.keyedPartsOf(ARD_BOOK_SERVER_ID) else emptyList(),
        )
        if (base == null) return fresh to playEventId
        return UserDataSnapshot(
            servers = if (servers) fresh.servers else base.servers,
            spokenServers = if (spokenServers) fresh.spokenServers else base.spokenServers,
            podcasts = if (feeds) fresh.podcasts else base.podcasts,
            listeningStats = if (stats) fresh.listeningStats else base.listeningStats,
            listeningDays = if (days) fresh.listeningDays else base.listeningDays,
            progress = if (progress) fresh.progress else base.progress,
            playlists = if (musicData != null) fresh.playlists else base.playlists,
            playlistEntries = if (musicData != null) fresh.playlistEntries else base.playlistEntries,
            favorites = if (musicData != null) fresh.favorites else base.favorites,
            playEvents = when {
                musicData != null -> fresh.playEvents
                newPlays != null -> base.playEvents + newPlays.map { UserPlayEventEntity(it.song.key, it.playedAt, it.listenedMs) }
                    .associateBy { PlayKey(it.song, it.playedAt) }
                else -> base.playEvents
            },
            wearSelections = if (musicData != null) fresh.wearSelections else base.wearSelections,
            episodeDownloads = if (downloads) fresh.episodeDownloads else base.episodeDownloads,
            collectionFavorites = if (musicData != null) fresh.collectionFavorites else base.collectionFavorites,
            bookmarks = if (bookmarks) fresh.bookmarks else base.bookmarks,
            episodeStars = if (stars) fresh.episodeStars else base.episodeStars,
            ardBooks = if (ards) fresh.ardBooks else base.ardBooks,
        ) to playEventId
    }

    private suspend fun readUser() = userDao.snapshot()

    private companion object {
        const val TAG = "UserDataBackup"
        const val RESTORED_MARKER = "user_data_restored"

        /** Feed IDs created by a restore and not synced yet, one per line ([pendingOf]). */
        const val RESTORED_FEEDS = "restored_feeds"
        const val MIRROR_INTERVAL_MS = 60_000L

        /** After a removal; short, but several removals in a row (selecting, undoing) still go in one mirroring. */
        const val REMOVAL_MIRROR_DELAY_MS = 2_000L
        const val FULL_READ_INTERVAL_MS = 30 * 60_000L
        val MUSIC_TABLES = arrayOf("songs", "albums", "artists", "playlists", "playlist_entries", "play_events", "wear_selections")
        const val APPLY_INTERVAL_MS = 5_000L
        const val ARD_ATTEMPTS = 3
        const val ARD_RETRY_MS = 30_000L
        const val MAX_ARGS = 500
        val MIRRORED_TABLES = arrayOf(
            "sources", "spoken_servers", "podcast_feeds", "listening_stats", "listening_days", "book_progress", "bookmarks",
            "songs", "albums", "artists", "playlists", "playlist_entries", "play_events", "wear_selections", "book_tracks",
            // Only for the saved episodes; then only their part is read.
            "books",
        )
    }
}

/** Servers, subscriptions and playlists of a full backup; on restore, the newly created ones. */
data class BackupCounts(
    val musicServers: Int = 0,
    val audiobookServers: Int = 0,
    val podcasts: Int = 0,
    val playlists: Int = 0,
)

/** Result of [UserDataBackup.import]: what was newly added, and IDs from the backup that are named differently here (backed-up → local). */
data class BackupImport(val counts: BackupCounts, val ids: Map<String, String>)

/** Backed-up data whose book, episode, song, album or playlist does not exist (yet); playlists by their key. */
internal data class Pending(
    val progress: List<UserBookProgressEntity> = emptyList(),
    val bookmarks: List<UserBookmarkEntity> = emptyList(),
    val playlists: List<String> = emptyList(),
    val favorites: List<UserFavoriteEntity> = emptyList(),
    val collectionFavorites: List<UserCollectionFavoriteEntity> = emptyList(),
    val playEvents: List<UserPlayEventEntity> = emptyList(),
    val wearSelections: List<UserWearSelectionEntity> = emptyList(),
    val episodeDownloads: List<UserEpisodeDownloadEntity> = emptyList(),
    val episodeStars: List<UserEpisodeStarEntity> = emptyList(),
    val ardBooks: List<UserArdBookEntity> = emptyList(),
    /** Restored subscriptions whose episodes are not there yet (see `PodcastDao.excludeAllFromAutomatic`). */
    val restoredFeeds: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = progress.isEmpty() && bookmarks.isEmpty() && playlists.isEmpty() && favorites.isEmpty() && collectionFavorites.isEmpty() && playEvents.isEmpty() &&
            wearSelections.isEmpty() && episodeDownloads.isEmpty() && episodeStars.isEmpty() && ardBooks.isEmpty() && restoredFeeds.isEmpty()

    companion object {
        fun of(saved: UserDataSnapshot) = Pending(
            progress = saved.progress.values.toList(),
            bookmarks = saved.bookmarks.values.toList(),
            playlists = saved.playlists.keys.toList(),
            favorites = saved.favorites.values.toList(),
            collectionFavorites = saved.collectionFavorites.values.toList(),
            playEvents = saved.playEvents.values.toList(),
            wearSelections = saved.wearSelections.values.toList(),
            episodeDownloads = saved.episodeDownloads.values.toList(),
            episodeStars = saved.episodeStars.values.toList(),
            ardBooks = saved.ardBooks.values.toList(),
        )
    }
}
