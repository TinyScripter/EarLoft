package keinabersmart.kleinaberlaut.core.model

/** Ob und wie ein Song ohne Netz verfügbar ist. */
enum class SongOfflineState {
    /** Liegt als lokale Datei vor – ein Download ist unnötig. */
    LOCAL,
    DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    FAILED,

    /** Nur per Stream verfügbar. */
    NONE,
}

data class SongOfflineStatus(
    val state: SongOfflineState,
    /** 0..1 während des Ladens, sonst `null`. */
    val progress: Float? = null,
)

/** Offline-Zustand aller Songs eines Albums. */
data class AlbumOfflineStatus(val songs: Map<Long, SongOfflineStatus>) {
    private fun count(state: SongOfflineState) = songs.values.count { it.state == state }

    val localCount: Int get() = count(SongOfflineState.LOCAL)
    val downloadedCount: Int get() = count(SongOfflineState.DOWNLOADED)
    val pendingCount: Int get() = count(SongOfflineState.QUEUED) + count(SongOfflineState.DOWNLOADING)
    val failedCount: Int get() = count(SongOfflineState.FAILED)

    /** Songs, die überhaupt heruntergeladen werden können (nicht lokal). */
    val downloadableCount: Int get() = songs.size - localCount

    val isFullyOffline: Boolean get() = songs.isNotEmpty() && localCount + downloadedCount == songs.size

    /** Hat das Album Downloads (fertig, laufend oder fehlgeschlagen)? */
    val hasDownloads: Boolean get() = downloadedCount + pendingCount + failedCount > 0

    companion object {
        val EMPTY = AlbumOfflineStatus(emptyMap())
    }
}

/** Ein Album mit mindestens einem Download – für die Downloads-Übersicht. */
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
    /** Fortschritt des gerade laufenden Downloads (0..1), sonst `null`. */
    val currentProgress: Float?,
) {
    companion object {
        val EMPTY = DownloadOverview(0, 0, 0, 0, 0, null)
    }
}
