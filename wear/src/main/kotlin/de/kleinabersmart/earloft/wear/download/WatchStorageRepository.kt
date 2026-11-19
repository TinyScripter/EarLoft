package de.kleinabersmart.earloft.wear.download

import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.DownloadOverview
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A selection from the phone; its songs are in the device playlist [playlistId] (Wear concept 7.2). */
data class SelectionItem(
    val playlistId: Long,
    val kind: WearSelectionKind,
    val title: String,
    val songCount: Int,
    /** Downloaded songs of the selection; `songCount` is all of them. */
    val downloadedCount: Int = songCount,
    /** [WearSelectionKind.key] with the phone's IDs, e.g. `album:12`. */
    val key: String = "",
    /** Fully downloaded bytes of the selection. */
    val bytes: Long = 0,
    /** Distinct songs of the selection (duplicates in a playlist count once, unlike [songCount]). */
    val distinctSongs: Int = songCount,
    /** Downloaded share of the songs currently downloading, summed up. */
    val runningFraction: Double = 0.0,
    /** Failed songs of the selection. */
    val failedCount: Int = 0,
) {
    /** Album or playlist ID on the phone (from [key]); `null` for the favorites. */
    val phoneRefId: Long? get() = key.substringAfter(':', "").toLongOrNull()

    /** Downloaded share 0–1; running songs count proportionally. */
    val progress: Float
        get() = if (distinctSongs == 0 || downloadedCount >= distinctSongs) {
            1f
        } else {
            ((downloadedCount + runningFraction) / distinctSongs).toFloat().coerceAtMost(1f)
        }
}

/** An episode or book on the watch; [serverId] and [remoteId] as on the phone (episode manifest, Wear concept 16.1/16.2). */
data class StoredSpoken(
    val bookId: Long,
    val kind: BookKind,
    val serverId: String,
    val remoteId: String,
    val title: String,
    val author: String,
    /** All files are downloaded. */
    val loaded: Boolean,
    /** Fully downloaded bytes. */
    val bytes: Long,
)

/** [progress]: downloaded share 0–1; [failed]: a file could not be downloaded, and none is waiting any more. */
data class SpokenDownload(val progress: Float, val failed: Boolean)

/**
 * Downloaded share of the files of an episode or book, weighted by their duration (the watch only learns the size while
 * downloading); [current] is the file of this book currently downloading.
 */
internal fun spokenProgress(tracks: List<BookTrackEntity>, current: SpokenFileProgress?): Float {
    if (tracks.isEmpty()) return 0f
    var total = 0.0
    var done = 0.0
    for (track in tracks) {
        val weight = track.durationMs.coerceAtLeast(1).toDouble()
        total += weight
        val running = current?.takeIf { it.trackIndex == track.trackIndex }
        val runningTotal = running?.total?.takeIf { it > 0 }
        done += when {
            track.downloadState == TrackDownload.DONE -> weight
            running != null && runningTotal != null -> weight * (running.done.toDouble() / runningTotal).coerceIn(0.0, 1.0)
            else -> 0.0
        }
    }
    return (done / total).toFloat()
}

/**
 * What is on the watch and what is still coming – for the UI (concept 4: repositories as the only interface to the
 * data layer; Review A1). The download logic itself (planner, worker) still reads the DAOs directly.
 */
@Singleton
class WatchStorageRepository @Inject constructor(
    private val wearDao: WearDao,
    private val downloadDao: DownloadDao,
    private val playlistRepository: PlaylistRepository,
    private val libraryRepository: LibraryRepository,
    private val watchState: WatchState,
    private val downloads: DownloadRepository,
    private val bookDao: BookDao,
) {

    /**
     * Progress of all downloads on the watch: songs as on the phone, plus the files of episodes and books (Wear concept 16.1/16.2) in
     * the used storage and under "still to download".
     */
    fun overview(): Flow<DownloadOverview> = combine(downloads.overview(), bookDao.observeTrackTotals()) { songs, spoken ->
        songs.copy(usedBytes = songs.usedBytes + spoken.doneBytes, pendingSongs = songs.pendingSongs + spoken.pendingCount)
    }

    /**
     * The selections from the phone with their device playlist and download progress (Wear concept 7.2), in the
     * phone's order; selections without a device playlist (not applied yet) are missing.
     */
    fun selections(): Flow<List<SelectionItem>> = combine(
        wearDao.observeSelections(),
        playlistRepository.playlists(),
        wearDao.observeSelectionProgress(),
    ) { selections, playlists, progress ->
        val byId = playlists.associateBy { it.id }
        val progressByKey = progress.associateBy { it.key }
        selections.mapNotNull { selection ->
            val playlist = selection.refId?.let { byId[it] } ?: return@mapNotNull null
            val progress = progressByKey[selection.key]
            SelectionItem(
                playlist.id,
                selection.kind,
                playlist.name,
                playlist.songCount,
                downloadedCount = progress?.done ?: 0,
                key = selection.key,
                bytes = progress?.bytes ?: 0,
                distinctSongs = progress?.total ?: playlist.songCount,
                runningFraction = progress?.runningFraction ?: 0.0,
                failedCount = progress?.failed ?: 0,
            )
        }
    }

    /** Episodes and books on the watch with the progress of their files; only those chosen on the phone are on the watch. */
    fun storedSpoken(): Flow<List<StoredSpoken>> = bookDao.observeStoredBooks().map { rows ->
        rows.map {
            StoredSpoken(it.bookId, it.kind, it.serverId, it.remoteId, it.title, it.author, loaded = it.total > 0 && it.done == it.total, bytes = it.doneBytes)
        }
    }

    /** Download progress of episode or book [bookId] on the watch; the file currently downloading counts proportionally. */
    fun spokenDownload(bookId: Long): Flow<SpokenDownload> =
        combine(bookDao.observeTracks(bookId), watchState.spokenFileProgress) { all, current ->
            // What is no longer available and was never downloaded will not come any more: the book is complete without it.
            val tracks = all.filter { it.unavailableSince == null || it.downloadState == TrackDownload.DONE }
            SpokenDownload(
                progress = spokenProgress(tracks, current?.takeIf { it.bookId == bookId }),
                // Only once nothing is waiting: until then the job still tries (via the phone too).
                failed = tracks.any { it.downloadState == TrackDownload.FAILED } &&
                    tracks.none { it.downloadState == TrackDownload.QUEUED || it.downloadState == TrackDownload.RUNNING },
            )
        }.distinctUntilChanged()

    /** Keys of all selections from the phone according to the last applied manifest ([WearSelectionKind.key]). */
    fun selectedKeys(): Flow<Set<String>> = wearDao.observeSelections().map { selections -> selections.mapTo(HashSet()) { it.key } }

    /** All fully downloaded songs. */
    suspend fun downloadedSongs(): List<Song> = libraryRepository.songs(downloadDao.getDoneSongIds())

    /**
     * Estimated bytes that come via the phone: songs only on the phone, server songs where the server failed too
     * often, and songs from a source the watch does not know (Wear concept 8.3).
     */
    suspend fun phoneBacklogBytes(quality: WearQuality): Long = wearDao.getPendingCandidates()
        .filter { it.sourceType == SourceType.PHONE || watchState.hasServerFailedTooOften(it.uid) || watchState.isMarkedViaPhone(it.uid) }
        .sumOf { estimateBytes(it, quality) }
}
