package keinabersmart.kleinaberlaut.wear.download

import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.DownloadOverview
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Auswahl vom Handy; ihre Songs liegen in der Geräte-Playlist [playlistId] (Wear-Konzept 7.2). */
data class SelectionItem(
    val playlistId: Long,
    val kind: WearSelectionKind,
    val title: String,
    val songCount: Int,
    /** Geladene Songs der Auswahl; `songCount` sind alle. */
    val downloadedCount: Int = songCount,
    /** [WearSelectionKind.key] mit den IDs des Handys, z. B. `album:12`. */
    val key: String = "",
)

/**
 * Was auf der Uhr liegt und was noch kommt – für die Oberfläche (Konzept 4: Repositories als einzige Schnittstelle zur
 * Datenschicht; Review A1). Die Download-Logik selbst (Planer, Worker) liest die DAOs weiter direkt.
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
     * Stand aller Downloads der Uhr: Songs wie am Handy, dazu die Dateien der Folgen und Bücher (Wear-Konzept 16.1/16.2) im
     * belegten Speicher und unter „noch zu laden“.
     */
    fun overview(): Flow<DownloadOverview> = combine(downloads.overview(), bookDao.observeTrackTotals()) { songs, spoken ->
        songs.copy(usedBytes = songs.usedBytes + spoken.doneBytes, pendingSongs = songs.pendingSongs + spoken.pendingCount)
    }

    /**
     * Die Auswahlen vom Handy mit ihrer Geräte-Playlist und dem Stand der Downloads (Wear-Konzept 7.2), in der
     * Reihenfolge des Handys; Auswahlen ohne Geräte-Playlist (noch nicht übernommen) fehlen.
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
            val done = progressByKey[selection.key]?.done ?: 0
            SelectionItem(playlist.id, selection.kind, playlist.name, playlist.songCount, downloadedCount = done, key = selection.key)
        }
    }

    /** Schlüssel aller Auswahlen vom Handy laut zuletzt übernommenem Manifest ([WearSelectionKind.key]). */
    fun selectedKeys(): Flow<Set<String>> = wearDao.observeSelections().map { selections -> selections.mapTo(HashSet()) { it.key } }

    /** Alle fertig geladenen Songs. */
    suspend fun downloadedSongs(): List<Song> = libraryRepository.songs(downloadDao.getDoneSongIds())

    /**
     * Geschätzte Bytes, die über das Handy kommen: Songs nur vom Handy und Server-Songs, bei denen der Server zu oft
     * scheiterte (Wear-Konzept 8.3).
     */
    suspend fun phoneBacklogBytes(quality: WearQuality): Long = wearDao.getPendingCandidates()
        .filter { it.sourceType == SourceType.PHONE || watchState.hasServerFailedTooOften(it.uid) }
        .sumOf { estimateBytes(it, quality) }
}
