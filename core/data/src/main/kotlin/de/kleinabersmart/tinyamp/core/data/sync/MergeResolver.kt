package de.kleinabersmart.tinyamp.core.data.sync

import de.kleinabersmart.tinyamp.core.common.normalizeForMerge
import de.kleinabersmart.tinyamp.core.common.sortKey
import de.kleinabersmart.tinyamp.core.data.repository.pushFavoriteToServers
import de.kleinabersmart.tinyamp.core.database.dao.MergeDao
import de.kleinabersmart.tinyamp.core.database.dao.SyncDao
import de.kleinabersmart.tinyamp.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.tinyamp.core.database.entity.AlbumEntity
import de.kleinabersmart.tinyamp.core.database.entity.ArtistEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.tinyamp.core.database.entity.SongEntity
import de.kleinabersmart.tinyamp.core.database.entity.SongSourceEntity
import de.kleinabersmart.tinyamp.core.datasource.RawArtist
import de.kleinabersmart.tinyamp.core.datasource.RawTrack
import javax.inject.Inject
import kotlin.math.abs

/**
 * Ordnet einen [RawTrack] einem logischen Song zu (Konzept 6.5).
 *
 * Muss innerhalb einer Datenbank-Transaktion aufgerufen werden.
 */
class MergeResolver @Inject constructor(
    private val mergeDao: MergeDao,
    private val syncDao: SyncDao,
) {

    /**
     * @param syncsFavorites `true` für Server: Deren Stern wird mit dem Favoriten des Songs abgeglichen.
     * @return die `songId`, der die Quelle zugeordnet wurde.
     */
    suspend fun merge(sourceId: String, track: RawTrack, generation: Long, syncsFavorites: Boolean = false): Long {
        val uid = "$sourceId:${track.remoteId}"
        val existing = mergeDao.getSongSource(uid)

        if (existing == null) {
            val songId = resolveSong(resolveAlbum(track), track)
            mergeDao.insertSongSource(track.toSongSource(uid, songId, sourceId, generation))
            mergeDao.setAlbumCoverIfMissing(mergeDao.getSong(songId)!!.albumId, uid)
            if (syncsFavorites) mergeFavorite(songId, uid, track.starredAt, previousStarredAt = null, isNewSource = true)
            return songId
        }

        if (existing.lastModified != null && existing.lastModified == track.lastModified) {
            mergeDao.markSeen(uid, generation)
            if (syncsFavorites && (existing.starredAt != null) != (track.starredAt != null)) {
                syncDao.setStarredAt(uid, track.starredAt)
                mergeFavorite(existing.songId, uid, track.starredAt, existing.starredAt, isNewSource = false)
            }
            return existing.songId
        }

        // Die Datei hat sich geändert (z. B. neu getaggt).
        val albumId = resolveAlbum(track)
        val songId = if (mergeDao.countSourcesOfSong(existing.songId) == 1) {
            // Einzige Quelle: Song in-place aktualisieren, damit songId (Favoriten, Playlists) erhalten bleibt.
            val song = mergeDao.getSong(existing.songId)!!
            mergeDao.updateSong(track.applyTo(song, albumId))
            linkSongArtists(song.songId, track.artists, replace = true)
            song.songId
        } else {
            // Der Song wird noch von anderen Quellen geliefert: nur diese Quelle neu zuordnen.
            resolveSong(albumId, track)
        }
        mergeDao.updateSongSource(track.toSongSource(uid, songId, sourceId, generation))
        // Ist die Datei selbst eine andere, wird ein Download neu geladen. Reine Metadaten-Änderungen
        // (z. B. Album-Cover) zählen nicht – angezeigt wird ohnehin, was in der Datenbank steht.
        if (existing.sizeBytes != track.sizeBytes || existing.mimeType != track.mimeType) mergeDao.requeueDownload(uid)
        mergeDao.setAlbumCoverIfMissing(albumId, uid)
        if (syncsFavorites) mergeFavorite(songId, uid, track.starredAt, existing.starredAt, isNewSource = false)
        return songId
    }

    /**
     * Favoriten zwischen Server und Song abgleichen (Konzept 6.4). Reagiert nur auf Änderungen:
     * - Server hat (ent)sternt → Song übernimmt das, andere Server folgen.
     * - Neue Server-Quelle für einen schon favorisierten Song → Stern wird an den Server gesendet,
     *   statt den lokalen Favoriten zu verlieren.
     * Hat der Song noch ungesendete lokale Änderungen, gewinnen diese.
     */
    private suspend fun mergeFavorite(songId: Long, uid: String, starredAt: Long?, previousStarredAt: Long?, isNewSource: Boolean) {
        if (syncDao.hasPendingFavorite(songId)) return
        val isFavorite = mergeDao.getSong(songId)?.isFavorite ?: return
        val starred = starredAt != null
        val serverChanged = if (isNewSource) starred else starred != (previousStarredAt != null)
        when {
            serverChanged && starred != isFavorite -> {
                syncDao.setSongFavorite(songId, starred, starredAt ?: System.currentTimeMillis())
                syncDao.pushFavoriteToServers(songId, starred, exceptUid = uid)
            }
            isNewSource && isFavorite && !starred -> syncDao.pushFavoriteToServers(songId, favorite = true)
        }
    }

    /**
     * Übernimmt den vollständigen Favoriten-Stand eines Servers ([starred]: `remoteId` → Zeitpunkt), ohne die
     * Songs selbst abzugleichen. Quellen, die der Server nicht (mehr) als favorisiert führt, gelten als entsternt.
     * Wie bei [merge] zählt nur, was sich gegenüber dem zuletzt bekannten Stand geändert hat.
     */
    suspend fun mergeStarred(sourceId: String, starred: Map<String, Long>) {
        for (state in syncDao.getStarStates(sourceId)) {
            val starredAt = starred[state.remoteId]
            if ((state.starredAt != null) == (starredAt != null)) continue
            syncDao.setStarredAt(state.uid, starredAt)
            mergeFavorite(state.songId, state.uid, starredAt, state.starredAt, isNewSource = false)
        }
    }

    private suspend fun resolveArtist(raw: RawArtist): Long {
        raw.mbid?.let { mbid -> mergeDao.findArtistByMbid(mbid)?.let { return it.artistId } }

        val normName = raw.name.mergeKey()
        val byName = mergeDao.findArtistByNormName(normName)
        if (byName != null && !conflicts(byName.mbid, raw.mbid)) {
            if (byName.mbid == null && raw.mbid != null) mergeDao.updateArtist(byName.copy(mbid = raw.mbid))
            return byName.artistId
        }
        return mergeDao.insertArtist(
            ArtistEntity(name = raw.name, sortName = raw.name.sortKey(), normName = normName, mbid = raw.mbid),
        )
    }

    private suspend fun resolveAlbum(track: RawTrack): Long {
        track.mbidAlbum?.let { mbid -> mergeDao.findAlbumByMbid(mbid)?.let { return it.albumId } }

        val candidates = mergeDao.findAlbums(track.albumArtistDisplay.mergeKey(), track.albumTitle.mergeKey())
            .filterNot { conflicts(it.mbid, track.mbidAlbum) }
        val match = candidates.firstOrNull { it.year == track.year }
            ?: candidates.singleOrNull()?.takeIf { it.year == null || track.year == null }

        if (match != null) {
            val filledIn = match.copy(year = match.year ?: track.year, mbid = match.mbid ?: track.mbidAlbum)
            if (filledIn != match) mergeDao.updateAlbum(filledIn)
            return match.albumId
        }

        val albumId = mergeDao.insertAlbum(
            AlbumEntity(
                title = track.albumTitle,
                sortTitle = track.albumTitle.sortKey(),
                normTitle = track.albumTitle.mergeKey(),
                albumArtistDisplay = track.albumArtistDisplay,
                normAlbumArtist = track.albumArtistDisplay.mergeKey(),
                year = track.year,
                isCompilation = track.isCompilation,
                mbid = track.mbidAlbum,
            ),
        )
        track.albumArtists.forEachIndexed { position, artist ->
            mergeDao.insertAlbumArtist(AlbumArtistCrossRef(albumId, resolveArtist(artist), position))
        }
        return albumId
    }

    private suspend fun resolveSong(albumId: Long, track: RawTrack): Long {
        track.mbidTrack?.let { mbid -> mergeDao.findSongByMbid(albumId, mbid)?.let { return it.songId } }

        val normTitle = track.title.mergeKey()
        val match = mergeDao.findSongByPosition(albumId, track.discNumber, track.trackNumber, normTitle)
            ?.takeUnless { conflicts(it.mbid, track.mbidTrack) }
            ?: mergeDao.findSongsByTitle(albumId, normTitle).firstOrNull {
                !conflicts(it.mbid, track.mbidTrack) && abs(it.durationMs - track.durationMs) < DURATION_TOLERANCE_MS
            }
        if (match != null) {
            // Wie bei Künstler und Album: Eine Quelle, die die MBID kennt, ergänzt sie für alle.
            if (match.mbid == null && track.mbidTrack != null) mergeDao.updateSong(match.copy(mbid = track.mbidTrack))
            return match.songId
        }

        val songId = mergeDao.insertSong(track.applyTo(null, albumId))
        linkSongArtists(songId, track.artists, replace = false)
        return songId
    }

    private suspend fun linkSongArtists(songId: Long, artists: List<RawArtist>, replace: Boolean) {
        if (replace) mergeDao.deleteSongArtists(songId)
        artists.forEachIndexed { position, artist ->
            mergeDao.insertSongArtist(SongArtistCrossRef(songId, resolveArtist(artist), position))
        }
    }

    private fun RawTrack.applyTo(song: SongEntity?, albumId: Long): SongEntity {
        val base = song ?: SongEntity(
            albumId = albumId,
            title = title,
            normTitle = title.mergeKey(),
            artistDisplay = artistDisplay,
            durationMs = durationMs,
            dateAdded = dateAdded ?: System.currentTimeMillis(),
        )
        return base.copy(
            albumId = albumId,
            title = title,
            normTitle = title.mergeKey(),
            artistDisplay = artistDisplay,
            discNumber = discNumber,
            trackNumber = trackNumber,
            durationMs = durationMs,
            year = year,
            genre = genre,
            mbid = mbidTrack ?: base.mbid,
        )
    }

    private fun RawTrack.toSongSource(uid: String, songId: Long, sourceId: String, generation: Long) =
        SongSourceEntity(
            uid = uid,
            songId = songId,
            sourceId = sourceId,
            remoteId = remoteId,
            coverRemoteId = coverRemoteId,
            mimeType = mimeType,
            bitRate = bitRate,
            sizeBytes = sizeBytes,
            lastModified = lastModified,
            rgTrackGainDb = rgTrackGainDb,
            rgTrackPeak = rgTrackPeak,
            rgAlbumGainDb = rgAlbumGainDb,
            rgAlbumPeak = rgAlbumPeak,
            seenInGeneration = generation,
            starredAt = starredAt,
        )

    /** Zwei unterschiedliche MBIDs bedeuten bewusst verschiedene Einträge. */
    private fun conflicts(a: String?, b: String?) = a != null && b != null && a != b

    private companion object {
        const val DURATION_TOLERANCE_MS = 3_000

        /** Fällt auf den Rohtext zurück, falls ein Name nur aus Satzzeichen besteht (z. B. „!!!“). */
        fun String.mergeKey(): String = normalizeForMerge().ifEmpty { trim().lowercase() }
    }
}
