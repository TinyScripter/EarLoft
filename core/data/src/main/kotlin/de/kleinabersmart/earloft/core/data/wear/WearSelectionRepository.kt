package de.kleinabersmart.earloft.core.data.wear

import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.repository.SourcePreference
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.database.model.WearSongRow
import de.kleinabersmart.earloft.core.database.model.WearSourceRow
import de.kleinabersmart.earloft.core.model.ReplayGain
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What is selected on the phone for the watch (Wear concept 5) and what follows from it. Applies to all
 * paired watches (W3). Resolving a selection into songs and sources only happens here.
 */
@Singleton
class WearSelectionRepository @Inject constructor(
    private val database: EarLoftDatabase,
    private val wearDao: WearDao,
    private val playlistDao: PlaylistDao,
) {
    /** Keys of the selections ([WearSelectionKind.key]). */
    fun selectedKeys(): Flow<Set<String>> =
        wearDao.observeSelections().map { selections -> selections.mapTo(HashSet()) { it.key } }

    suspend fun setSelected(kind: WearSelectionKind, refId: Long?, selected: Boolean) {
        val key = kind.key(refId)
        if (!selected) {
            wearDao.delete(key)
            return
        }
        // Selecting again keeps the original timestamp (download order on the watch).
        database.withTransaction {
            if (wearDao.getSelection(key) == null) {
                wearDao.upsert(WearSelectionEntity(key, kind, refId, addedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun clear() = wearDao.deleteAll()

    /**
     * [load], emitted anew on every change of the involved tables ("live selection"). Throttled: a
     * sync changes the tables in many transactions; in between it re-resolves at most every
     * [THROTTLE_MS], the last state always arrives.
     */
    fun content(): Flow<WearContent> =
        database.invalidationTracker.createFlow(*OBSERVED_TABLES)
            .conflate()
            .transform {
                emit(load())
                delay(THROTTLE_MS)
            }
            .distinctUntilChanged()

    /**
     * Resolves all selections. Selections whose album or playlist no longer exists are
     * removed in the process (a foreign key isn't possible because `refId` points to two tables).
     */
    suspend fun load(): WearContent = database.withTransaction {
        wearDao.deleteDangling()
        val selections = wearDao.getSelections().mapNotNull { resolve(it) }
        val songIds = selections.flatMap { it.songIds }.distinct()
        WearContent(selections, songs(songIds))
    }

    private suspend fun resolve(selection: WearSelectionEntity): WearSelectionContent? {
        val refId = selection.refId
        val (title, songIds) = when (selection.kind) {
            WearSelectionKind.ALBUM -> {
                val title = refId?.let { wearDao.getAlbumTitle(it) } ?: return null
                title to wearDao.getAlbumSongIds(refId)
            }
            WearSelectionKind.PLAYLIST -> {
                val playlist = refId?.let { playlistDao.getPlaylist(it) } ?: return null
                playlist.name to playlistDao.getSongIds(refId)
            }
            // Favorited albums come along in full (like the automatic download on the phone), after the favorited songs.
            WearSelectionKind.FAVORITES -> "" to (wearDao.getFavoriteSongIds() + wearDao.getFavoriteAlbumSongIds()).distinct()
        }
        return WearSelectionContent(selection.key, selection.kind, refId, title, songIds)
    }

    private suspend fun songs(songIds: List<Long>): List<WearSongContent> {
        val rows = HashMap<Long, WearSongRow>()
        val artists = HashMap<Long, MutableList<String>>()
        val sources = HashMap<Long, MutableList<WearSourceRow>>()
        songIds.chunked(MAX_SQL_VARIABLES).forEach { chunk ->
            wearDao.getSongs(chunk).associateByTo(rows) { it.songId }
            wearDao.getSongArtists(chunk).forEach { artists.getOrPut(it.songId) { mutableListOf() } += it.name }
            wearDao.getSources(chunk).forEach { sources.getOrPut(it.songId) { mutableListOf() } += it }
        }
        return songIds.mapNotNull { songId ->
            val row = rows[songId] ?: return@mapNotNull null
            val songSources = sources[songId]?.sortedWith(compareBy(SourcePreference) { it.toPlayable() })
            if (songSources.isNullOrEmpty()) return@mapNotNull null
            row.toContent(artists[songId].orEmpty(), songSources)
        }
    }

    private fun WearSongRow.toContent(artists: List<String>, sources: List<WearSourceRow>): WearSongContent {
        val servers = sources.filter { it.sourceType.isServer }
        val phoneFile = sources.firstOrNull { it.sourceType == SourceType.LOCAL }
            ?.let { WearPhoneFile(it.uid, it.sizeBytes, it.mimeType) }
            // As during playback: if the file is missing (deleted by the system), the download doesn't count.
            ?: servers.firstOrNull { it.downloadPath?.let(::File)?.isFile == true }
                ?.let { WearPhoneFile(it.uid, it.downloadBytes ?: it.sizeBytes, it.mimeType) }
        return WearSongContent(
            songId = songId,
            key = sources.first().uid,
            title = title,
            artists = artists,
            artistDisplay = artistDisplay,
            album = albumTitle,
            albumArtist = albumArtistDisplay,
            isCompilation = isCompilation,
            discNumber = discNumber,
            trackNumber = trackNumber,
            durationMs = durationMs,
            year = year,
            genre = genre,
            mbid = mbid,
            albumMbid = albumMbid,
            replayGain = sources.firstNotNullOfOrNull { source ->
                ReplayGain(source.rgTrackGainDb, source.rgTrackPeak, source.rgAlbumGainDb, source.rgAlbumPeak)
                    .takeUnless { it.isEmpty }
            },
            serverSources = servers.map {
                WearServerSource(it.uid, it.sourceId, it.remoteId, it.mimeType, it.sizeBytes, it.bitRate, it.coverRemoteId)
            },
            phoneFile = phoneFile,
        )
    }

    private fun WearSourceRow.toPlayable() =
        PlayableSourceRow(uid, songId, sourceId, remoteId, coverRemoteId, sourceType, sourcePriority, mimeType, bitRate)

    private companion object {
        const val THROTTLE_MS = 2_000L
        const val MAX_SQL_VARIABLES = 900

        /** Everything the selections, song details and sources depend on. */
        val OBSERVED_TABLES = arrayOf(
            "wear_selections", "albums", "songs", "song_sources", "song_artists", "artists",
            "playlists", "playlist_entries", "downloads", "sources",
        )
    }
}
