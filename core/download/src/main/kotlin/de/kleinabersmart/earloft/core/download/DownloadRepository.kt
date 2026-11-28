package de.kleinabersmart.earloft.core.download

import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.SourcePreference
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadExclusionEntity
import de.kleinabersmart.earloft.core.database.model.SongOfflineRow
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumDownloadSummary
import de.kleinabersmart.earloft.core.model.AlbumOfflineStatus
import de.kleinabersmart.earloft.core.model.DownloadOverview
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.DownloadedAlbum
import de.kleinabersmart.earloft.core.model.OfflineMark
import de.kleinabersmart.earloft.core.model.SongOfflineLookup
import de.kleinabersmart.earloft.core.model.SongOfflineState
import de.kleinabersmart.earloft.core.model.SongOfflineStatus
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Downloading, removing and displaying downloads (concept 2, "Offline"). */
@Singleton
class DownloadRepository @Inject constructor(
    private val database: EarLoftDatabase,
    private val downloadDao: DownloadDao,
    private val libraryDao: LibraryDao,
    private val playlistDao: PlaylistDao,
    private val storage: DownloadStorage,
    private val scheduler: DownloadScheduler,
    private val coverFiles: CoverFiles,
) {
    private val requests = AtomicLong()

    /** Per song the [requests] count of the last manual download request that covered it (song, album, artist, playlist). */
    private val requestedAt = ConcurrentHashMap<Long, Long>()

    /** Taken when a removal starts waiting for its "Undo" snackbar; see [removeSongsNotRequestedSince]. */
    fun requestStamp(): Long = requests.get()

    /**
     * The removal of [songIds] that waited since [stamp] for its "Undo" snackbar: songs downloaded again in the meantime as part of
     * an album, artist or playlist stay (review5 8.12); otherwise the expired snackbar would delete them and lock them out.
     */
    suspend fun removeSongsNotRequestedSince(songIds: List<Long>, stamp: Long) =
        removeSongs(songIds.filter { (requestedAt[it] ?: 0) <= stamp })

    suspend fun downloadAlbum(albumId: Long): Int =
        enqueue(libraryDao.getSongIdsOfAlbum(albumId), pinnedBy = "album:$albumId")

    suspend fun downloadSongs(songIds: List<Long>): Int = enqueue(songIds, pinnedBy = PINNED_MANUAL)

    /** Downloads all albums whose album artist is [artistId] – album by album, as if manually (downloads overview per album). */
    suspend fun downloadArtist(artistId: Long): Int = downloadDao.getAlbumIdsOfArtist(artistId).sumOf { downloadAlbum(it) }

    suspend fun downloadPlaylist(playlistId: Long): Int =
        enqueue(playlistDao.getSongIds(playlistId), pinnedBy = "playlist:$playlistId")

    /**
     * Enqueues the best server source for each song. Songs with a local file or an existing
     * download are skipped; failed downloads of these songs are retried. An automation ([isAutomatic]) skips songs whose
     * download the user removed; a manual download takes them back (concept 5.3 "Removed by the user").
     * @return number of newly enqueued or retried songs.
     */
    private suspend fun enqueue(songIds: List<Long>, pinnedBy: String, retryFailed: Boolean = true): Int {
        if (songIds.isEmpty()) return 0
        val count = database.withTransaction {
            val wanted = songIds.distinct().chunked(SQLITE_MAX_VARIABLES)
            val chunks = if (isAutomatic(pinnedBy)) {
                val excluded = wanted.flatMapTo(HashSet()) { downloadDao.getExcluded(it) }
                wanted.flatten().filter { it !in excluded }.chunked(SQLITE_MAX_VARIABLES)
            } else {
                val request = requests.incrementAndGet()
                wanted.forEach { chunk -> chunk.forEach { requestedAt[it] = request } }
                wanted.forEach { downloadDao.unexclude(it) }
                wanted
            }
            // A download belongs to the strongest rule that wants it (manual > whole server > newest/most played albums >
            // favorites): whoever requests an automatically downloaded song themselves keeps it even without a favorite.
            weakerPins(pinnedBy).forEach { weaker -> chunks.forEach { downloadDao.repinForSongs(weaker, pinnedBy, it) } }
            val sourcesBySong = chunks.flatMap { libraryDao.getPlayableSourcesOfSongs(it) }.groupBy { it.songId }
            val alreadyDownloaded = chunks.flatMapTo(HashSet()) { downloadDao.getSongIdsWithDownload(it) }

            val newDownloads = sourcesBySong
                .filter { (songId, sources) ->
                    songId !in alreadyDownloaded && sources.none { it.sourceType == SourceType.LOCAL }
                }
                .map { (songId, sources) ->
                    DownloadEntity(
                        sourceUid = sources.sortedWith(SourcePreference).first().uid,
                        songId = songId,
                        state = DownloadState.QUEUED,
                        pinnedBy = pinnedBy,
                    )
                }
            downloadDao.insertIfAbsent(newDownloads)
            val failed = if (retryFailed) {
                chunks.flatMap { downloadDao.getForSongs(it) }.filter { it.state == DownloadState.FAILED }.map { it.sourceUid }
            } else {
                emptyList()
            }
            val retried = failed.chunked(SQLITE_MAX_VARIABLES).sumOf { downloadDao.retryFailed(it) }
            newDownloads.size + retried
        }
        if (count > 0) scheduler.enqueue()
        return count
    }

    /** The user removes downloads (including waiting ones): the automations no longer download these songs ([removeByUser]). */
    suspend fun removeAlbum(albumId: Long) = removeByUser(downloadDao.getForAlbum(albumId))

    suspend fun removeArtist(artistId: Long) = downloadDao.getAlbumIdsOfArtist(artistId).forEach { removeAlbum(it) }

    suspend fun removeSongs(songIds: List<Long>) =
        removeByUser(songIds.distinct().chunked(SQLITE_MAX_VARIABLES).flatMap { downloadDao.getForSongs(it) })

    /**
     * Removes the playlist's downloads without excluding its songs from automations: playlists often contain
     * favorites or automatic albums, so removing a playlist download must not lock them out (review5 P34). A song an automation
     * wants right now isn't deleted either but goes back to it, so it needn't be downloaded again (review5 8.12).
     */
    suspend fun removePlaylist(playlistId: Long) {
        val songIds = playlistDao.getSongIds(playlistId).distinct()
        val downloads = songIds.chunked(SQLITE_MAX_VARIABLES).flatMap { downloadDao.getForSongs(it) }
        val wanted = automationsWanting(songIds)
        val (kept, removed) = downloads.partition { it.songId in wanted }
        database.withTransaction {
            kept.filter { it.pinnedBy != wanted.getValue(it.songId) }
                .groupBy { wanted.getValue(it.songId) }
                .forEach { (pin, list) -> list.map { it.sourceUid }.chunked(SQLITE_MAX_VARIABLES).forEach { downloadDao.repin(it, pin) } }
        }
        remove(removed)
    }

    /** The rules the automations last reconciled with ([AutoDownloads]); `null` before the first reconcile. */
    @Volatile private var favoritesRule: Boolean? = null

    @Volatile private var favoriteArtistsRule = false

    @Volatile private var serverRule: Set<String> = emptySet()

    @Volatile private var autoAlbumsRule: List<Long>? = null

    /**
     * Of [songIds], those an automation wants right now, with its pin; the strongest wins as in [enqueue] (whole server > newest and
     * most played albums > favorites). Songs the user removed are left out, as the automations skip them.
     */
    private suspend fun automationsWanting(songIds: List<Long>): Map<Long, String> {
        val wanted = HashMap<Long, String>()
        if (favoritesRule == true) {
            val favorites = downloadDao.getFavoriteSongIds(favoriteArtistsRule).toHashSet()
            songIds.filter { it in favorites }.forEach { wanted[it] = PINNED_FAVORITES }
        }
        autoAlbumsRule?.let { albumIds ->
            val songs = albumIds.flatMapTo(HashSet()) { libraryDao.getSongIdsOfAlbum(it) }
            songIds.filter { it in songs }.forEach { wanted[it] = PINNED_AUTO_ALBUMS }
        }
        val servers = serverRule
        if (servers.isNotEmpty()) {
            songIds.chunked(SQLITE_MAX_VARIABLES).flatMap { libraryDao.getPlayableSourcesOfSongs(it) }
                .filter { it.sourceId in servers }
                .forEach { wanted[it.songId] = PINNED_SERVER_PREFIX + it.sourceId }
        }
        if (wanted.isEmpty()) return wanted
        val excluded = wanted.keys.toList().chunked(SQLITE_MAX_VARIABLES).flatMapTo(HashSet()) { downloadDao.getExcluded(it) }
        return wanted - excluded
    }

    /**
     * Removed by the user: the songs are remembered so that no automation brings them back on its next reconcile (previously a
     * removed favorite came back right away). Downloading them manually releases them again. Removals by the automations
     * themselves ([remove]) remember nothing.
     */
    private suspend fun removeByUser(downloads: List<DownloadEntity>) {
        if (downloads.isEmpty()) return
        exclude(downloads.map { it.sourceUid }.chunked(SQLITE_MAX_VARIABLES).flatMap { downloadDao.getSongIdsOfSources(it) })
        remove(downloads)
    }

    private suspend fun exclude(songIds: List<Long>) {
        val now = System.currentTimeMillis()
        songIds.distinct().chunked(SQLITE_MAX_VARIABLES).forEach { chunk ->
            downloadDao.exclude(chunk.map { DownloadExclusionEntity(it, now) })
        }
    }

    /**
     * Reconciles the automatic downloads with the favorites ([enabled] = setting): favorites and the songs of
     * favorited albums – with [withArtists] also those of favorited artists – are downloaded, what nobody wants anymore
     * is removed. If the automation is off, its downloads are kept (now as manual). A song whose download the user removed
     * stays removed until they download it themselves ([removeByUser]).
     */
    suspend fun reconcileFavoriteDownloads(enabled: Boolean, withArtists: Boolean = false) {
        favoritesRule = enabled
        favoriteArtistsRule = withArtists
        if (!enabled) {
            downloadDao.repinAll(PINNED_FAVORITES, PINNED_MANUAL)
            return
        }
        remove(downloadDao.getPinnedByWithoutFavorite(PINNED_FAVORITES, withArtists))
        // Don't retry failed ones on every change: otherwise a broken song slows down every reconcile.
        enqueue(downloadDao.getFavoriteSongIds(withArtists), PINNED_FAVORITES, retryFailed = false)
    }

    /** What the favorites automation wants to download (see [reconcileFavoriteDownloads]). */
    fun favoriteSongIds(withArtists: Boolean): Flow<List<Long>> = downloadDao.observeFavoriteSongIds(withArtists)

    /**
     * Reconciles the servers from which everything should be downloaded ([sourceIds] = setting): their songs
     * are enqueued (including newly added ones), favorite downloads of these songs are transferred to the server rule.
     * For servers that were switched off the files are kept (now as manual), as with favorites. Whatever is no longer
     * on the server disappears with its song source anyway.
     */
    suspend fun reconcileServerDownloads(sourceIds: Set<String>) {
        serverRule = sourceIds
        downloadDao.getPinnedByWithPrefix(PINNED_SERVER_PREFIX)
            .filter { it.removePrefix(PINNED_SERVER_PREFIX) !in sourceIds }
            .forEach { downloadDao.repinAll(it, PINNED_MANUAL) }
        sourceIds.forEach { sourceId ->
            // As with favorites: don't retry failed ones on every reconcile.
            enqueue(downloadDao.getSongIdsOfSource(sourceId), PINNED_SERVER_PREFIX + sourceId, retryFailed = false)
        }
    }

    /**
     * The albums the automation should download as "newest" ([newest]) and "most played" ([mostPlayed], like the shelf on Home),
     * combined; `null` if both are off (0). The period for "most played" is recalculated hourly.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun autoAlbumIds(newest: Int, mostPlayed: Int): Flow<List<Long>?> {
        if (newest <= 0 && mostPlayed <= 0) return flowOf(null)
        val mostPlayedIds = LibraryRepository.windowStarts(LibraryRepository.MOST_PLAYED_PERIOD_MS).flatMapLatest { since ->
            downloadDao.observeMostPlayedServerAlbumIds(since, LibraryRepository.MOST_PLAYED_MIN_PLAYS, mostPlayed.coerceAtLeast(0))
        }
        return combine(
            downloadDao.observeNewestServerAlbumIds(newest.coerceAtLeast(0)),
            mostPlayedIds,
        ) { newestIds, mostPlayedIds -> (newestIds + mostPlayedIds).distinct() }
    }

    /**
     * Reconciles the automatically downloaded newest and most played albums ([albumIds] from [autoAlbumIds]): their songs
     * are enqueued, downloads of albums that dropped out are removed – favorites are handed over to the favorites automation
     * if it is on ([keepFavorites], with [favoriteArtists] as there). If both are off (`null`), their downloads are kept
     * (now as manual), only the favorites' ones still go to its automation.
     */
    suspend fun reconcileAlbumDownloads(albumIds: List<Long>?, keepFavorites: Boolean, favoriteArtists: Boolean = false) {
        autoAlbumsRule = albumIds
        if (albumIds == null) {
            if (keepFavorites) downloadDao.repinFavoritesOutsideAlbums(PINNED_AUTO_ALBUMS, PINNED_FAVORITES, emptyList(), favoriteArtists)
            downloadDao.repinAll(PINNED_AUTO_ALBUMS, PINNED_MANUAL)
            return
        }
        if (keepFavorites) downloadDao.repinFavoritesOutsideAlbums(PINNED_AUTO_ALBUMS, PINNED_FAVORITES, albumIds, favoriteArtists)
        remove(downloadDao.getPinnedByOutsideAlbums(PINNED_AUTO_ALBUMS, albumIds))
        // As with favorites: don't retry failed ones on every reconcile.
        enqueue(albumIds.flatMap { libraryDao.getSongIdsOfAlbum(it) }, PINNED_AUTO_ALBUMS, retryFailed = false)
    }

    /** Offline state of arbitrary songs, for all lists. */
    fun songStatuses(): Flow<SongOfflineLookup> =
        combine(downloadDao.observeDownloadRows(), downloadDao.observeLocalSongIds()) { rows, local ->
            SongOfflineLookup(rows.associate { it.songId to it.toStatus() }, local.toHashSet())
        }

    /** "Remove all": like [removeByUser], the automations leave the songs alone afterwards. */
    suspend fun removeAll() {
        val all = downloadDao.getAll()
        exclude(downloadDao.getSongIdsOfAllDownloads())
        downloadDao.deleteAll()
        withContext(Dispatchers.IO) { all.forEach(storage::delete) }
    }

    /** A running download notices the removal at its next progress update and aborts. */
    private suspend fun remove(downloads: List<DownloadEntity>) {
        if (downloads.isEmpty()) return
        downloads.map { it.sourceUid }.chunked(SQLITE_MAX_VARIABLES).forEach { downloadDao.delete(it) }
        withContext(Dispatchers.IO) { downloads.forEach(storage::delete) }
        // The cover stays: it belongs to the offline images as long as the album exists (`OfflineImages`).
    }

    /** [replace]: the job starts now instead of after a waiting one (e.g. one that only runs on the charger, review4 T62). */
    suspend fun retryFailed(replace: Boolean = false) {
        if (downloadDao.retryFailed() > 0) scheduler.enqueue(replace)
    }

    /** After saving a server (e.g. new password): retry its failed downloads. */
    suspend fun retryFailedOf(sourceId: String) {
        if (downloadDao.retryFailedOfSource(sourceId) > 0) scheduler.enqueue()
    }

    /** After app start: resume waiting downloads in case WorkManager lost the job. */
    suspend fun resumePending() {
        // Even without waiting downloads: the job fetches missing covers of downloaded albums.
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload().any { !coverFiles.has(it) }
        if (downloadDao.countPending() > 0 || coversMissing) scheduler.resume()
    }

    /** After changing the Wi-Fi setting, replace the job with new constraints. */
    suspend fun rescheduleAfterSettingsChange() {
        if (downloadDao.countPending() > 0) scheduler.enqueue(replace = true)
    }

    fun albumStatus(albumId: Long): Flow<AlbumOfflineStatus> =
        downloadDao.observeOfflineRowsOfAlbum(albumId)
            .map { rows -> AlbumOfflineStatus(rows.associate { it.songId to it.toStatus() }) }
            .distinctUntilChanged()

    /** Combined download state of all albums of an artist (like [albumStatus]). */
    fun artistStatus(artistId: Long): Flow<AlbumOfflineStatus> =
        downloadDao.observeOfflineRowsOfArtist(artistId)
            .map { rows -> AlbumOfflineStatus(rows.associate { it.songId to it.toStatus() }) }
            .distinctUntilChanged()

    /** Marker per album with a download (tiles, rows); albums without a download are missing. */
    fun albumMarks(): Flow<Map<Long, OfflineMark>> =
        downloadDao.observeAlbumSummaries().map { rows ->
            buildMap {
                rows.forEach { row ->
                    val summary = AlbumDownloadSummary(row.songCount, row.localCount, row.doneCount, row.pendingCount, row.failedCount)
                    summary.mark?.let { put(row.albumId, it) }
                }
            }
        }.distinctUntilChanged()

    fun downloadedAlbums(): Flow<List<DownloadedAlbum>> =
        downloadDao.observeAlbumRows().map { rows ->
            rows.map {
                DownloadedAlbum(
                    album = Album(
                        id = it.albumId,
                        title = it.title,
                        artist = it.albumArtistDisplay,
                        year = it.year,
                        songCount = it.songCount,
                    ),
                    downloadedSongs = it.doneCount,
                    pendingSongs = it.pendingCount,
                    failedSongs = it.failedCount,
                    sizeBytes = it.doneBytes,
                )
            }
        }

    fun overview(): Flow<DownloadOverview> =
        downloadDao.observeTotals().map { totals ->
            DownloadOverview(
                downloadedSongs = totals.doneCount,
                pendingSongs = totals.pendingCount,
                failedSongs = totals.failedCount,
                usedBytes = totals.doneBytes,
                freeBytes = storage.freeBytes(),
                currentProgress = totals.runningBytesTotal.takeIf { it > 0 }
                    ?.let { (totals.runningBytesDone.toFloat() / it).coerceIn(0f, 1f) },
            )
        }.flowOn(Dispatchers.IO)

    private fun SongOfflineRow.toStatus(): SongOfflineStatus = when {
        isLocal -> SongOfflineStatus(SongOfflineState.LOCAL)
        else -> when (state) {
            null -> SongOfflineStatus(SongOfflineState.NONE)
            DownloadState.DONE -> SongOfflineStatus(SongOfflineState.DOWNLOADED)
            DownloadState.QUEUED -> SongOfflineStatus(SongOfflineState.QUEUED)
            DownloadState.FAILED -> SongOfflineStatus(SongOfflineState.FAILED)
            DownloadState.RUNNING -> SongOfflineStatus(
                SongOfflineState.DOWNLOADING,
                progress = bytesTotal?.takeIf { it > 0 }?.let { ((bytesDone ?: 0).toFloat() / it).coerceIn(0f, 1f) },
            )
        }
    }

    private companion object {
        const val PINNED_MANUAL = "manual"
        const val PINNED_FAVORITES = "favorites"
        const val PINNED_AUTO_ALBUMS = "auto-albums"
        const val PINNED_SERVER_PREFIX = "server:"
        const val SQLITE_MAX_VARIABLES = 900

        /** Downloads of an automation (not requested by the user): they skip songs the user removed. */
        fun isAutomatic(pinnedBy: String): Boolean =
            pinnedBy == PINNED_FAVORITES || pinnedBy == PINNED_AUTO_ALBUMS || pinnedBy.startsWith(PINNED_SERVER_PREFIX)

        /** The automatic rules whose downloads [pinnedBy] takes over. */
        fun weakerPins(pinnedBy: String): List<String> = when (pinnedBy) {
            PINNED_FAVORITES -> emptyList()
            PINNED_AUTO_ALBUMS -> listOf(PINNED_FAVORITES)
            else -> listOf(PINNED_FAVORITES, PINNED_AUTO_ALBUMS)
        }
    }
}
