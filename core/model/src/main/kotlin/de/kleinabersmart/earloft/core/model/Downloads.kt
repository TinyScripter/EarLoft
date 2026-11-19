package de.kleinabersmart.earloft.core.model

/** Whether and how a song is available without network. */
enum class SongOfflineState {
    /** Exists as a local file – a download is unnecessary. */
    LOCAL,
    DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    FAILED,

    /** Only available via streaming. */
    NONE,
}

data class SongOfflineStatus(
    val state: SongOfflineState,
    /** 0..1 while downloading, otherwise `null`. */
    val progress: Float? = null,
)

/** Offline state of all songs of an album. */
data class AlbumOfflineStatus(val songs: Map<Long, SongOfflineStatus>) {
    private fun count(state: SongOfflineState) = songs.values.count { it.state == state }

    val localCount: Int get() = count(SongOfflineState.LOCAL)
    val downloadedCount: Int get() = count(SongOfflineState.DOWNLOADED)
    val pendingCount: Int get() = count(SongOfflineState.QUEUED) + count(SongOfflineState.DOWNLOADING)
    val failedCount: Int get() = count(SongOfflineState.FAILED)

    /** Songs that can be downloaded at all (not local). */
    val downloadableCount: Int get() = songs.size - localCount

    val isFullyOffline: Boolean get() = songs.isNotEmpty() && localCount + downloadedCount == songs.size

    /** Does the album have downloads (finished, running or failed)? */
    val hasDownloads: Boolean get() = downloadedCount + pendingCount + failedCount > 0

    companion object {
        val EMPTY = AlbumOfflineStatus(emptyMap())
    }
}

/**
 * Offline state of arbitrary songs: local songs and songs with a download are known, all others
 * are [SongOfflineState.NONE].
 */
class SongOfflineLookup(
    private val downloads: Map<Long, SongOfflineStatus>,
    private val local: Set<Long>,
) {
    operator fun get(songId: Long): SongOfflineStatus = when {
        songId in local -> LOCAL
        else -> downloads[songId] ?: NONE
    }

    fun statusOf(songIds: Collection<Long>): AlbumOfflineStatus =
        AlbumOfflineStatus(songIds.distinct().associateWith { get(it) })

    companion object {
        private val LOCAL = SongOfflineStatus(SongOfflineState.LOCAL)
        private val NONE = SongOfflineStatus(SongOfflineState.NONE)
        val EMPTY = SongOfflineLookup(emptyMap(), emptySet())
    }
}

/** "Downloaded" marker on covers and in rows; without a download there is none (`null`). */
enum class OfflineMark {
    /** Fully downloaded. */
    DONE,

    /** Only partially downloaded (album). */
    PARTIAL,

    /** Waiting or downloading. */
    PENDING,
    FAILED,
}

/** Download state of an album in numbers, for tiles and rows; [localCount] counts songs with a local file. */
data class AlbumDownloadSummary(
    val songCount: Int,
    val localCount: Int,
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
) {
    /** Downloading takes precedence over failed, failed over finished; local songs count as present but don't mark anything on their own. */
    val mark: OfflineMark?
        get() = when {
            pendingCount > 0 -> OfflineMark.PENDING
            failedCount > 0 -> OfflineMark.FAILED
            doneCount == 0 -> null
            doneCount + localCount >= songCount -> OfflineMark.DONE
            else -> OfflineMark.PARTIAL
        }
}

/** An album with at least one download – for the downloads overview. */
data class DownloadedAlbum(
    val album: Album,
    val downloadedSongs: Int,
    val pendingSongs: Int,
    val failedSongs: Int,
    val sizeBytes: Long,
)

data class DownloadOverview(
    val downloadedSongs: Int,
    val pendingSongs: Int,
    val failedSongs: Int,
    val usedBytes: Long,
    val freeBytes: Long,
    /** Progress of the currently running download (0..1), otherwise `null`. */
    val currentProgress: Float?,
) {
    companion object {
        val EMPTY = DownloadOverview(0, 0, 0, 0, 0, null)
    }
}
