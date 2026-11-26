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
    suspend fun downloadAlbum(albumId: Long): Int =
        enqueue(libraryDao.getSongIdsOfAlbum(albumId), pinnedBy = "album:$albumId")

    suspend fun downloadSongs(songIds: List<Long>): Int = enqueue(songIds, pinnedBy = PINNED_MANUAL)

    /** Downloads all albums whose album artist is [artistId] – album by album, as if manually (downloads overview per album). */
    suspend fun downloadArtist(artistId: Long): Int = downloadDao.getAlbumIdsOfArtist(artistId).sumOf { downloadAlbum(it) }

    suspend fun downloadPlaylist(playlistId: Long): Int =
        enqueue(playlistDao.getSongIds(playlistId), pinnedBy = "playlist:$playlistId")

    /**
     * Enqueues the best server source for each song. Songs with a local file or an existing
     * download are skipped; failed downloads of these songs are retried.
     * @return number of newly enqueued or retried songs.
     */
    private suspend fun enqueue(songIds: List<Long>, pinnedBy: String, retryFailed: Boolean = true): Int {
        if (songIds.isEmpty()) return 0
        val count = database.withTransaction {
            val chunks = songIds.distinct().chunked(SQLITE_MAX_VARIABLES)
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

    suspend fun removeAlbum(albumId: Long) = remove(downloadDao.getForAlbum(albumId))

    suspend fun removeArtist(artistId: Long) = downloadDao.getAlbumIdsOfArtist(artistId).forEach { removeAlbum(it) }

    suspend fun removeSongs(songIds: List<Long>) =
        remove(songIds.distinct().chunked(SQLITE_MAX_VARIABLES).flatMap { downloadDao.getForSongs(it) })

    suspend fun removePlaylist(playlistId: Long) = removeSongs(playlistDao.getSongIds(playlistId))

    /**
     * Reconciles the automatic downloads with the favorites ([enabled] = setting): favorites and the songs of
     * favorited albums – with [withArtists] also those of favorited artists – are downloaded, what nobody wants anymore
     * is removed. If the automation is off, its downloads are kept (now as manual). Whoever removes an automatically
     * downloaded song afterwards gets it back on the next reconcile while the automation is on.
     */
    suspend fun reconcileFavoriteDownloads(enabled: Boolean, withArtists: Boolean = false) {
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

    suspend fun removeAll() {
        val all = downloadDao.getAll()
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

        /** The automatic rules whose downloads [pinnedBy] takes over. */
        fun weakerPins(pinnedBy: String): List<String> = when (pinnedBy) {
            PINNED_FAVORITES -> emptyList()
            PINNED_AUTO_ALBUMS -> listOf(PINNED_FAVORITES)
            else -> listOf(PINNED_FAVORITES, PINNED_AUTO_ALBUMS)
        }
    }
}
