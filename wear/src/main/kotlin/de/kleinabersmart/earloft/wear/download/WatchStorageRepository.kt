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
    /** Fertig geladene Bytes der Auswahl. */
    val bytes: Long = 0,
    /** Verschiedene Songs der Auswahl (Duplikate einer Playlist zählen einmal, anders als [songCount]). */
    val distinctSongs: Int = songCount,
    /** Geladener Anteil der gerade laufenden Songs, aufsummiert. */
    val runningFraction: Double = 0.0,
    /** Fehlgeschlagene Songs der Auswahl. */
    val failedCount: Int = 0,
) {
    /** Album- bzw. Playlist-ID am Handy (aus [key]); `null` bei den Favoriten. */
    val phoneRefId: Long? get() = key.substringAfter(':', "").toLongOrNull()

    /** Geladener Anteil 0–1; laufende Songs zählen anteilig. */
    val progress: Float
        get() = if (distinctSongs == 0 || downloadedCount >= distinctSongs) {
            1f
        } else {
            ((downloadedCount + runningFraction) / distinctSongs).toFloat().coerceAtMost(1f)
        }
}

/** Eine Folge bzw. ein Buch auf der Uhr; [serverId] und [remoteId] wie am Handy (Folgen-Manifest, Wear-Konzept 16.1/16.2). */
data class StoredSpoken(
    val bookId: Long,
    val kind: BookKind,
    val serverId: String,
    val remoteId: String,
    val title: String,
    val author: String,
    /** Alle Dateien sind geladen. */
    val loaded: Boolean,
    /** Fertig geladene Bytes. */
    val bytes: Long,
)

/** [progress]: geladener Anteil 0–1; [failed]: Eine Datei ließ sich nicht laden, und es wartet keine mehr. */
data class SpokenDownload(val progress: Float, val failed: Boolean)

/**
 * Geladener Anteil der Dateien einer Folge bzw. eines Buchs, nach ihrer Dauer gewichtet (die Größe kennt die Uhr erst beim
 * Laden); [current] ist die gerade ladende Datei dieses Buchs.
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

    /** Folgen und Bücher auf der Uhr mit dem Stand ihrer Dateien; auf der Uhr liegen nur die am Handy gewählten. */
    fun storedSpoken(): Flow<List<StoredSpoken>> = bookDao.observeStoredBooks().map { rows ->
        rows.map {
            StoredSpoken(it.bookId, it.kind, it.serverId, it.remoteId, it.title, it.author, loaded = it.total > 0 && it.done == it.total, bytes = it.doneBytes)
        }
    }

    /** Download-Stand von Folge bzw. Buch [bookId] auf der Uhr; die gerade ladende Datei zählt anteilig. */
    fun spokenDownload(bookId: Long): Flow<SpokenDownload> =
        combine(bookDao.observeTracks(bookId), watchState.spokenFileProgress) { all, current ->
            // Was nicht mehr abrufbar ist und nie geladen wurde, kommt nicht mehr: Das Buch ist ohne es fertig.
            val tracks = all.filter { it.unavailableSince == null || it.downloadState == TrackDownload.DONE }
            SpokenDownload(
                progress = spokenProgress(tracks, current?.takeIf { it.bookId == bookId }),
                // Erst, wenn nichts mehr wartet: Bis dahin versucht es der Auftrag noch (auch über das Handy).
                failed = tracks.any { it.downloadState == TrackDownload.FAILED } &&
                    tracks.none { it.downloadState == TrackDownload.QUEUED || it.downloadState == TrackDownload.RUNNING },
            )
        }.distinctUntilChanged()

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
