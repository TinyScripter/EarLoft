package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.common.foldedSortKey
import de.kleinabersmart.earloft.core.data.repository.audioQuality
import de.kleinabersmart.earloft.core.data.repository.pushFavoriteToServers
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongGenreEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.model.Genre
import javax.inject.Inject
import kotlin.math.abs

/**
 * Assigns a [RawTrack] to a logical song (concept 6.5).
 *
 * Must be called within a database transaction.
 */
class MergeResolver @Inject constructor(
    private val mergeDao: MergeDao,
    private val syncDao: SyncDao,
    private val collections: CollectionSourceResolver,
) {

    /**
     * @param syncsFavorites `true` for servers: their star is synced with the song's favorite.
     * @return the `songId` the source was assigned to.
     */
    suspend fun merge(sourceId: String, track: RawTrack, generation: Long, syncsFavorites: Boolean = false): Long {
        val uid = "$sourceId:${track.remoteId}"
        val existing = mergeDao.getSongSource(uid)

        if (existing == null) {
            val songId = resolveSong(resolveAlbum(track), track, sourceId, uid)
            mergeDao.insertSongSource(track.toSongSource(uid, songId, sourceId, generation))
            mergeDao.setAlbumCoverIfMissing(mergeDao.getSong(songId)!!.albumId, uid)
            if (syncsFavorites) {
                mergeFavorite(songId, uid, track.starredAt, previousStarredAt = null, isNewSource = true)
                collections.record(sourceId, track, songId, generation)
            }
            return songId
        }

        if (existing.lastModified != null && existing.lastModified == track.lastModified) {
            mergeDao.markSeen(uid, generation)
            if (syncsFavorites && (existing.starredAt != null) != (track.starredAt != null)) {
                syncDao.setStarredAt(uid, track.starredAt)
                mergeFavorite(existing.songId, uid, track.starredAt, existing.starredAt, isNewSource = false)
            }
            if (syncsFavorites) collections.record(sourceId, track, existing.songId, generation)
            return existing.songId
        }

        // The file has changed (e.g. retagged).
        val albumId = resolveAlbum(track)
        val songId = if (mergeDao.countSourcesOfSong(existing.songId) == 1) {
            // Only source: update the song in place so that songId (favorites, playlists) is preserved.
            val song = mergeDao.getSong(existing.songId)!!
            mergeDao.updateSong(track.applyTo(song, albumId))
            linkSongArtists(song.songId, track.artists, replace = true)
            linkSongGenres(song.songId, track.allGenres, replace = true)
            song.songId
        } else {
            // The song is still delivered by other sources: only reassign this source.
            resolveSong(albumId, track, sourceId, uid)
        }
        mergeDao.updateSongSource(track.toSongSource(uid, songId, sourceId, generation))
        // If the file itself is a different one, a download is re-fetched. Pure metadata changes
        // (e.g. album cover) don't count – what's displayed is whatever is in the database anyway.
        if (existing.sizeBytes != track.sizeBytes || existing.mimeType != track.mimeType) mergeDao.requeueDownload(uid)
        mergeDao.setAlbumCoverIfMissing(albumId, uid)
        if (syncsFavorites) {
            // Switched to a different song (split or retagged): for that song the source is new, its star comes along
            // (review V13). Otherwise it would remain on the server while the song isn't a favorite in the app.
            mergeFavorite(songId, uid, track.starredAt, existing.starredAt, isNewSource = songId != existing.songId)
            collections.record(sourceId, track, songId, generation)
        }
        return songId
    }

    /**
     * Sync favorites between server and song (concept 6.4). Only reacts to changes:
     * - Server has (un)starred → song adopts that, other servers follow.
     * - New server source for an already favorited song → star is sent to the server
     *   instead of losing the local favorite.
     * If the song still has unsent local changes, those win.
     */
    private suspend fun mergeFavorite(songId: Long, uid: String, starredAt: Long?, previousStarredAt: Long?, isNewSource: Boolean) {
        if (syncDao.hasPendingFavorite(songId)) return
        val isFavorite = mergeDao.getSong(songId)?.isFavorite ?: return
        val starred = starredAt != null
        val serverChanged = if (isNewSource) starred else starred != (previousStarredAt != null)
        when {
            serverChanged && starred != isFavorite -> {
                // Jellyfin/Emby don't know a timestamp (0): then now applies, otherwise the favorite would be at the very bottom (1970).
                syncDao.setSongFavorite(songId, starred, starredAt?.takeIf { it > 0 } ?: System.currentTimeMillis())
                syncDao.pushFavoriteToServers(songId, starred, exceptUid = uid)
            }
            isNewSource && isFavorite && !starred -> syncDao.pushFavoriteToServers(songId, favorite = true)
        }
    }

    /**
     * Adopts the complete favorites state of a server ([starred]: `remoteId` → timestamp) without syncing the
     * songs themselves. Sources the server doesn't list (anymore) as favorited count as unstarred.
     * As with [merge], only what has changed compared to the last known state counts.
     */
    suspend fun mergeStarred(sourceId: String, starred: Map<String, Long>) {
        for (state in syncDao.getStarStates(sourceId)) {
            val starredAt = starred[state.remoteId]
            if ((state.starredAt != null) == (starredAt != null)) continue
            syncDao.setStarredAt(state.uid, starredAt)
            mergeFavorite(state.songId, state.uid, starredAt, state.starredAt, isNewSource = false)
        }
    }

    /**
     * Before the sweep of a full sync of [sourceId] (review G1): a track the server now delivers under a new ID (Jellyfin/Emby
     * after moving a folder, Navidrome after a retag) became a second song in [merge], because the old source of the same
     * server still blocked the old song (V10). For every source about to be swept whose song loses its last source of this
     * server, a song of the same album that only this server's new source delivers and that matches by MBID or by position,
     * title and length is joined with it: history, playlist entries, queue, lyrics, favorite, a removed download (so the
     * automations don't fetch it again) and the downloaded file (if it is the same file) are kept. Two tracks that the server still delivers both stay two songs.
     *
     * Must be called within the sweep transaction, before [MergeDao.deleteStaleSongSources].
     */
    suspend fun adoptChangedIds(sourceId: String, generation: Long) {
        val taken = mutableSetOf<String>()
        for (stale in mergeDao.getStaleSongSources(sourceId, generation)) {
            val old = mergeDao.getSong(stale.songId) ?: continue
            if (mergeDao.hasFreshSourceFrom(old.songId, sourceId, generation)) continue
            val candidate = mergeDao.findSoleFreshSources(old.albumId, old.songId, sourceId, generation)
                .filterNot { it.uid in taken }
                .firstOrNull { source -> mergeDao.getSong(source.songId)?.isSameTrackAs(old) == true }
                ?: continue
            val new = mergeDao.getSong(candidate.songId) ?: continue
            taken += candidate.uid
            // A song that keeps other sources (e.g. the local file) stays; otherwise the new one with the current tags does.
            val keepOld = mergeDao.countSurvivingSources(old.songId, sourceId, generation) > 0
            val (from, into) = if (keepOld) new to old else old to new
            if (keepOld) {
                mergeDao.moveSongSource(candidate.uid, old.songId)
                mergeDao.moveDownloadsOfSong(new.songId, old.songId)
            }
            if (stale.sizeBytes == candidate.sizeBytes && stale.mimeType == candidate.mimeType) {
                mergeDao.moveDownload(stale.uid, candidate.uid, into.songId)
            }
            mergeDao.retargetSyncActions(sourceId, stale.uid, candidate.uid)
            mergeDao.movePlayEvents(from.songId, into.songId)
            mergeDao.movePlaylistEntries(from.songId, into.songId)
            mergeDao.moveQueueItems(from.songId, into.songId)
            mergeDao.moveLyrics(from.songId, into.songId)
            mergeDao.moveDownloadExclusions(from.songId, into.songId)
            if (from.isFavorite && !into.isFavorite) {
                syncDao.setSongFavorite(into.songId, true, from.favoriteChangedAt)
                if (candidate.starredAt == null) syncDao.pushFavoriteToServers(into.songId, favorite = true)
            }
            // `from` loses its last source with the sweep and is removed by cleanupOrphans.
        }
    }

    private fun SongEntity.isSameTrackAs(other: SongEntity): Boolean {
        if (conflicts(mbid, other.mbid)) return false
        if (mbid != null && mbid == other.mbid) return true
        return discNumber == other.discNumber && trackNumber == other.trackNumber && normTitle == other.normTitle &&
            abs(durationMs - other.durationMs) < DURATION_TOLERANCE_MS
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
            ArtistEntity(name = raw.name, sortName = raw.name.foldedSortKey(), normName = normName, mbid = raw.mbid),
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
                sortTitle = track.albumTitle.foldedSortKey(),
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

    /**
     * The song the track of source [sourceId] (identifier [uid]) belongs to, otherwise a new one. Only a song that
     * this source doesn't already deliver is found: two tracks of one source are two songs, even with the same title and almost
     * the same length (e.g. "Walk This Way" by Aerosmith and by Run-DMC on the same album), with the same MBID (one recording twice
     * on a deluxe edition) or at the same position (two "Interlude"s without track number, review V10).
     */
    private suspend fun resolveSong(albumId: Long, track: RawTrack, sourceId: String, uid: String): Long {
        suspend fun SongEntity.isFree() = !mergeDao.hasOtherSourceFrom(songId, sourceId, uid)

        track.mbidTrack?.let { mbid -> mergeDao.findSongByMbid(albumId, mbid)?.takeIf { it.isFree() }?.let { return it.songId } }

        val normTitle = track.title.mergeKey()
        val match = mergeDao.findSongByPosition(albumId, track.discNumber, track.trackNumber, normTitle)
            ?.takeIf { !conflicts(it.mbid, track.mbidTrack) && it.isFree() }
            ?: mergeDao.findSongsByTitle(albumId, normTitle).firstOrNull {
                !conflicts(it.mbid, track.mbidTrack) && abs(it.durationMs - track.durationMs) < DURATION_TOLERANCE_MS && it.isFree()
            }
        if (match != null) {
            // As with artist and album: a source that knows the MBID fills it in for all.
            var updated = if (match.mbid == null && track.mbidTrack != null) match.copy(mbid = track.mbidTrack) else match
            if (hasBestQuality(match.songId, track, uid)) {
                linkSongGenres(match.songId, track.allGenres, replace = true)
                updated = updated.copy(genre = track.allGenres.first())
            }
            if (updated != match) mergeDao.updateSong(updated)
            return match.songId
        }

        val songId = mergeDao.insertSong(track.applyTo(null, albumId))
        linkSongArtists(songId, track.artists, replace = false)
        linkSongGenres(songId, track.allGenres, replace = false)
        return songId
    }

    /**
     * If a song has several sources, its genres come from the one with the highest quality (lossless, then bitrate;
     * review V11): `true` if [track] (identifier [uid]) has genres and is better than every other source of the song. On a
     * tie the existing ones stay, otherwise they would change with every sync.
     */
    private suspend fun hasBestQuality(songId: Long, track: RawTrack, uid: String): Boolean {
        if (track.allGenres.isEmpty()) return false
        val incoming = audioQuality(track.mimeType, track.bitRate)
        return mergeDao.getSourcesOfSong(songId).none { it.uid != uid && audioQuality(it.mimeType, it.bitRate) >= incoming }
    }

    /**
     * The song's genres: those of the source that created or last changed it; with several sources those of the best
     * ([hasBestQuality]).
     */
    private suspend fun linkSongGenres(songId: Long, genres: List<String>, replace: Boolean) {
        if (replace) mergeDao.deleteSongGenres(songId)
        val rows = genres.distinctBy(Genre::key).mapIndexed { position, genre -> SongGenreEntity(songId, genre, Genre.key(genre), position) }
        if (rows.isNotEmpty()) mergeDao.insertSongGenres(rows)
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
            genre = allGenres.firstOrNull(),
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

    /** Two different MBIDs deliberately mean different entries. */
    private fun conflicts(a: String?, b: String?) = a != null && b != null && a != b

    private companion object {
        const val DURATION_TOLERANCE_MS = 3_000
    }
}

/** Falls back to the raw text if a name consists only of punctuation (e.g. "!!!"). */
internal fun String.mergeKey(): String = normalizeForMerge().ifEmpty { trim().lowercase() }
