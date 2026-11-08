package de.kleinabersmart.earloft.core.model

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

/**
 * Offline-Zustand beliebiger Songs: Lokale Songs und Songs mit Download sind bekannt, alle anderen
 * sind [SongOfflineState.NONE].
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

/** Markierung „heruntergeladen“ auf Covern und in Zeilen; ohne Download gibt es keine (`null`). */
enum class OfflineMark {
    /** Ganz geladen. */
    DONE,

    /** Nur ein Teil geladen (Album). */
    PARTIAL,

    /** Wartet oder lädt. */
    PENDING,
    FAILED,
}

/** Download-Stand eines Albums in Zahlen, für Kacheln und Zeilen; [localCount] zählt Songs mit lokaler Datei. */
data class AlbumDownloadSummary(
    val songCount: Int,
    val localCount: Int,
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
) {
    /** Laden geht vor Fehler, Fehler vor fertig; lokale Songs zählen als vorhanden, markieren aber allein nichts. */
    val mark: OfflineMark?
        get() = when {
            pendingCount > 0 -> OfflineMark.PENDING
            failedCount > 0 -> OfflineMark.FAILED
            doneCount == 0 -> null
            doneCount + localCount >= songCount -> OfflineMark.DONE
            else -> OfflineMark.PARTIAL
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
