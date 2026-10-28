package keinabersmart.kleinaberlaut.core.data.wear

import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.data.repository.SourcePreference
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.PlaylistDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.entity.WearSelectionEntity
import keinabersmart.kleinaberlaut.core.database.model.PlayableSourceRow
import keinabersmart.kleinaberlaut.core.database.model.WearSongRow
import keinabersmart.kleinaberlaut.core.database.model.WearSourceRow
import keinabersmart.kleinaberlaut.core.model.ReplayGain
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was am Handy für die Uhr ausgewählt ist (Wear-Konzept 5) und was daraus folgt. Gilt für alle
 * gekoppelten Uhren (W3). Das Auflösen einer Auswahl in Songs und Quellen gibt es nur hier.
 */
@Singleton
class WearSelectionRepository @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val wearDao: WearDao,
    private val playlistDao: PlaylistDao,
) {
    /** Schlüssel der Auswahlen ([WearSelectionKind.key]). */
    fun selectedKeys(): Flow<Set<String>> =
        wearDao.observeSelections().map { selections -> selections.mapTo(HashSet()) { it.key } }

    suspend fun setSelected(kind: WearSelectionKind, refId: Long?, selected: Boolean) {
        val key = kind.key(refId)
        if (!selected) {
            wearDao.delete(key)
            return
        }
        // Erneutes Auswählen behält den ursprünglichen Zeitpunkt (Download-Reihenfolge auf der Uhr).
        database.withTransaction {
            if (wearDao.getSelection(key) == null) {
                wearDao.upsert(WearSelectionEntity(key, kind, refId, addedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun clear() = wearDao.deleteAll()

    /** Was sich auf der Uhr auswählen lässt: Favoriten und alle Playlists, auch Geräte-Playlists (Wear-Konzept 5). */
    fun catalog(): Flow<WearCatalog> =
        combine(wearDao.observeFavoriteCount(), playlistDao.observePlaylistRows(), selectedKeys()) { favorites, playlists, keys ->
            WearCatalog(
                favoritesCount = favorites,
                favoritesSelected = WearSelectionKind.FAVORITES.key(null) in keys,
                playlists = playlists.map {
                    WearCatalogPlaylist(
                        playlistId = it.playlistId,
                        name = it.name,
                        songCount = it.songCount,
                        serverName = it.serverName,
                        selected = WearSelectionKind.PLAYLIST.key(it.playlistId) in keys,
                    )
                },
            )
        }.distinctUntilChanged()

    /**
     * [load], neu bei jeder Änderung der beteiligten Tabellen („lebende Auswahl“). Gedrosselt: Ein
     * Abgleich ändert die Tabellen in vielen Transaktionen; dazwischen wird höchstens alle
     * [THROTTLE_MS] neu aufgelöst, der letzte Stand kommt immer an.
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
     * Löst alle Auswahlen auf. Auswahlen, deren Album bzw. Playlist es nicht mehr gibt, werden dabei
     * entfernt (ein Fremdschlüssel geht nicht, weil `refId` auf zwei Tabellen zeigt).
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
            WearSelectionKind.FAVORITES -> "" to wearDao.getFavoriteSongIds()
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
        val servers = sources.filter { it.sourceType == SourceType.SUBSONIC }
        val phoneFile = sources.firstOrNull { it.sourceType == SourceType.LOCAL }
            ?.let { WearPhoneFile(it.uid, it.sizeBytes, it.mimeType) }
            // Wie beim Abspielen: Fehlt die Datei (vom System gelöscht), gilt der Download nicht.
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

        /** Alles, wovon Auswahlen, Song-Angaben und Quellen abhängen. */
        val OBSERVED_TABLES = arrayOf(
            "wear_selections", "albums", "songs", "song_sources", "song_artists", "artists",
            "playlists", "playlist_entries", "downloads", "sources",
        )
    }
}
