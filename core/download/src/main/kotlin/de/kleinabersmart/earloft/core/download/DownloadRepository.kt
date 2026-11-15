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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Herunterladen, Entfernen und Anzeigen von Downloads (Konzept 2, „Offline“). */
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

    /** Lädt alle Alben, deren Album-Künstler [artistId] ist – Album für Album, wie von Hand (Downloads-Übersicht je Album). */
    suspend fun downloadArtist(artistId: Long): Int = downloadDao.getAlbumIdsOfArtist(artistId).sumOf { downloadAlbum(it) }

    suspend fun downloadPlaylist(playlistId: Long): Int =
        enqueue(playlistDao.getSongIds(playlistId), pinnedBy = "playlist:$playlistId")

    /**
     * Reiht für jeden Song die beste Server-Quelle ein. Songs mit lokaler Datei oder vorhandenem
     * Download werden übersprungen; fehlgeschlagene Downloads dieser Songs neu versucht.
     * @return Anzahl neu eingereihter oder neu versuchter Songs.
     */
    private suspend fun enqueue(songIds: List<Long>, pinnedBy: String, retryFailed: Boolean = true): Int {
        if (songIds.isEmpty()) return 0
        val count = database.withTransaction {
            val chunks = songIds.distinct().chunked(SQLITE_MAX_VARIABLES)
            // Ein Download gehört der stärksten Regel, die ihn will (von Hand > ganzer Server > neueste/meistgehörte Alben >
            // Favoriten): Wer einen automatisch geladenen Song selbst anfordert, behält ihn auch ohne Favorit.
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
     * Gleicht die automatischen Downloads mit den Favoriten ab ([enabled] = Einstellung): Favoriten und die Songs
     * favorisierter Alben – mit [withArtists] auch die favorisierter Künstler – werden geladen, was keiner mehr will,
     * entfernt. Ist die Automatik aus, behalten ihre Downloads (nun als manuell). Wer einen automatisch geladenen Song
     * danach selbst entfernt, bekommt ihn bei eingeschalteter Automatik beim nächsten Abgleich wieder.
     */
    suspend fun reconcileFavoriteDownloads(enabled: Boolean, withArtists: Boolean = false) {
        if (!enabled) {
            downloadDao.repinAll(PINNED_FAVORITES, PINNED_MANUAL)
            return
        }
        remove(downloadDao.getPinnedByWithoutFavorite(PINNED_FAVORITES, withArtists))
        // Fehlgeschlagene nicht bei jeder Änderung neu versuchen: Sonst bremst ein kaputter Song jeden Abgleich.
        enqueue(downloadDao.getFavoriteSongIds(withArtists), PINNED_FAVORITES, retryFailed = false)
    }

    /** Was die Favoriten-Automatik laden will (siehe [reconcileFavoriteDownloads]). */
    fun favoriteSongIds(withArtists: Boolean): Flow<List<Long>> = downloadDao.observeFavoriteSongIds(withArtists)

    /**
     * Gleicht die Server ab, von denen alles geladen werden soll ([sourceIds] = Einstellung): Ihre Songs
     * werden eingereiht (auch neu hinzugekommene), Favoriten-Downloads dieser Songs gehen an den Server über.
     * Für abgeschaltete Server bleiben die Dateien (nun als manuell), wie bei den Favoriten. Was nicht mehr
     * auf dem Server liegt, verschwindet ohnehin mit seiner Song-Quelle.
     */
    suspend fun reconcileServerDownloads(sourceIds: Set<String>) {
        downloadDao.getPinnedByWithPrefix(PINNED_SERVER_PREFIX)
            .filter { it.removePrefix(PINNED_SERVER_PREFIX) !in sourceIds }
            .forEach { downloadDao.repinAll(it, PINNED_MANUAL) }
        sourceIds.forEach { sourceId ->
            // Wie bei den Favoriten: Fehlgeschlagene nicht bei jedem Abgleich neu versuchen.
            enqueue(downloadDao.getSongIdsOfSource(sourceId), PINNED_SERVER_PREFIX + sourceId, retryFailed = false)
        }
    }

    /**
     * Die Alben, die die Automatik „neueste“ ([newest]) und „meistgehörte“ ([mostPlayed], wie das Regal auf Start) laden soll,
     * zusammen; `null`, wenn beide aus sind (0). Der Zeitraum für „meistgehört“ zählt ab jetzt.
     */
    fun autoAlbumIds(newest: Int, mostPlayed: Int): Flow<List<Long>?> {
        if (newest <= 0 && mostPlayed <= 0) return flowOf(null)
        val since = System.currentTimeMillis() - LibraryRepository.MOST_PLAYED_PERIOD_MS
        return combine(
            downloadDao.observeNewestServerAlbumIds(newest.coerceAtLeast(0)),
            downloadDao.observeMostPlayedServerAlbumIds(since, LibraryRepository.MOST_PLAYED_MIN_PLAYS, mostPlayed.coerceAtLeast(0)),
        ) { newestIds, mostPlayedIds -> (newestIds + mostPlayedIds).distinct() }
    }

    /**
     * Gleicht die automatisch geladenen neuesten und meistgehörten Alben ab ([albumIds] aus [autoAlbumIds]): Ihre Songs
     * werden eingereiht, Downloads herausgefallener Alben entfernt – Favoriten gehen dabei an die Favoriten-Automatik über,
     * wenn sie eingeschaltet ist ([keepFavorites], mit [favoriteArtists] wie dort). Sind beide aus (`null`), behalten ihre
     * Downloads (nun als manuell), nur die der Favoriten gehen auch dann an deren Automatik.
     */
    suspend fun reconcileAlbumDownloads(albumIds: List<Long>?, keepFavorites: Boolean, favoriteArtists: Boolean = false) {
        if (albumIds == null) {
            if (keepFavorites) downloadDao.repinFavoritesOutsideAlbums(PINNED_AUTO_ALBUMS, PINNED_FAVORITES, emptyList(), favoriteArtists)
            downloadDao.repinAll(PINNED_AUTO_ALBUMS, PINNED_MANUAL)
            return
        }
        if (keepFavorites) downloadDao.repinFavoritesOutsideAlbums(PINNED_AUTO_ALBUMS, PINNED_FAVORITES, albumIds, favoriteArtists)
        remove(downloadDao.getPinnedByOutsideAlbums(PINNED_AUTO_ALBUMS, albumIds))
        // Wie bei den Favoriten: Fehlgeschlagene nicht bei jedem Abgleich neu versuchen.
        enqueue(albumIds.flatMap { libraryDao.getSongIdsOfAlbum(it) }, PINNED_AUTO_ALBUMS, retryFailed = false)
    }

    /** Offline-Zustand beliebiger Songs, für alle Listen. */
    fun songStatuses(): Flow<SongOfflineLookup> =
        combine(downloadDao.observeDownloadRows(), downloadDao.observeLocalSongIds()) { rows, local ->
            SongOfflineLookup(rows.associate { it.songId to it.toStatus() }, local.toHashSet())
        }

    suspend fun removeAll() {
        val all = downloadDao.getAll()
        downloadDao.deleteAll()
        withContext(Dispatchers.IO) { all.forEach(storage::delete) }
    }

    /** Ein laufender Download bemerkt das Entfernen beim nächsten Fortschritt und bricht ab. */
    private suspend fun remove(downloads: List<DownloadEntity>) {
        if (downloads.isEmpty()) return
        downloads.map { it.sourceUid }.chunked(SQLITE_MAX_VARIABLES).forEach { downloadDao.delete(it) }
        withContext(Dispatchers.IO) { downloads.forEach(storage::delete) }
        // Das Cover bleibt: Es gehört zu den Bildern für offline, solange es das Album gibt (`OfflineImages`).
    }

    suspend fun retryFailed() {
        if (downloadDao.retryFailed() > 0) scheduler.enqueue()
    }

    /** Nach dem Speichern eines Servers (z. B. neues Passwort): seine gescheiterten Downloads erneut versuchen. */
    suspend fun retryFailedOf(sourceId: String) {
        if (downloadDao.retryFailedOfSource(sourceId) > 0) scheduler.enqueue()
    }

    /** Nach App-Start: Wartende Downloads fortsetzen, falls WorkManager den Auftrag verloren hat. */
    suspend fun resumePending() {
        // Auch ohne wartende Downloads: Der Auftrag holt fehlende Cover heruntergeladener Alben nach.
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload().any { !coverFiles.has(it) }
        if (downloadDao.countPending() > 0 || coversMissing) scheduler.resume()
    }

    /** Nach Ändern der WLAN-Einstellung den Auftrag mit neuen Bedingungen ersetzen. */
    suspend fun rescheduleAfterSettingsChange() {
        if (downloadDao.countPending() > 0) scheduler.enqueue(replace = true)
    }

    fun albumStatus(albumId: Long): Flow<AlbumOfflineStatus> =
        downloadDao.observeOfflineRowsOfAlbum(albumId)
            .map { rows -> AlbumOfflineStatus(rows.associate { it.songId to it.toStatus() }) }
            .distinctUntilChanged()

    /** Download-Zustand aller Alben eines Künstlers zusammen (wie [albumStatus]). */
    fun artistStatus(artistId: Long): Flow<AlbumOfflineStatus> =
        downloadDao.observeOfflineRowsOfArtist(artistId)
            .map { rows -> AlbumOfflineStatus(rows.associate { it.songId to it.toStatus() }) }
            .distinctUntilChanged()

    /** Markierung je Album mit Download (Kacheln, Zeilen); Alben ohne Download fehlen. */
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

        /** Die automatischen Regeln, deren Downloads [pinnedBy] übernimmt. */
        fun weakerPins(pinnedBy: String): List<String> = when (pinnedBy) {
            PINNED_FAVORITES -> emptyList()
            PINNED_AUTO_ALBUMS -> listOf(PINNED_FAVORITES)
            else -> listOf(PINNED_FAVORITES, PINNED_AUTO_ALBUMS)
        }
    }
}
