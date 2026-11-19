package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.data.repository.mergeCollectionFavorite
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.AlbumSourceEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistSourceEntity
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.model.FavoriteKind
import javax.inject.Inject

/**
 * Remembers under which ID a server lists the album and artists of a song (`album_sources`,
 * `artist_sources`), so that their favorites can be synced. Call within the merge transaction.
 *
 * If a server ID points to a different album than before (newly merged), it counts as a new source there: its star
 * carries over to the album, or it stars an already favorited album. Within one sync it sticks with the
 * first album, so that the songs of a server album spread across two albums don't push it back and forth.
 */
class CollectionSourceResolver @Inject constructor(
    private val mergeDao: MergeDao,
    private val syncDao: SyncDao,
) {
    suspend fun record(sourceId: String, track: RawTrack, songId: Long, generation: Long) {
        track.albumRemoteId?.let { remoteId ->
            val albumId = mergeDao.getSong(songId)?.albumId ?: return@let
            recordAlbum(sourceId, remoteId, albumId, generation)
        }
        val artists = (track.artists + track.albumArtists).filter { it.remoteId != null }.distinctBy { it.remoteId }
        if (artists.isEmpty()) return
        val linked = mergeDao.getArtistsOfSong(songId)
        for (raw in artists) {
            val artist = raw.mbid?.let { mbid -> linked.firstOrNull { it.mbid == mbid } }
                ?: raw.name.mergeKey().let { normName -> linked.firstOrNull { it.normName == normName } }
                ?: continue
            recordArtist(sourceId, raw.remoteId!!, artist.artistId, generation)
        }
    }

    private suspend fun recordAlbum(sourceId: String, remoteId: String, albumId: Long, generation: Long) {
        val existing = mergeDao.getAlbumSource(sourceId, remoteId)
        when {
            existing == null -> {
                mergeDao.insertAlbumSource(AlbumSourceEntity(sourceId, remoteId, albumId, generation))
                syncDao.mergeCollectionFavorite(FavoriteKind.ALBUM, albumId, sourceId, remoteId, starredAt = null, isNewSource = true)
            }
            existing.seenInGeneration == generation -> Unit
            existing.albumId != albumId -> {
                mergeDao.updateAlbumSource(existing.copy(albumId = albumId, seenInGeneration = generation))
                syncDao.mergeCollectionFavorite(FavoriteKind.ALBUM, albumId, sourceId, remoteId, existing.starredAt, isNewSource = true)
            }
            else -> mergeDao.updateAlbumSource(existing.copy(seenInGeneration = generation))
        }
    }

    private suspend fun recordArtist(sourceId: String, remoteId: String, artistId: Long, generation: Long) {
        val existing = mergeDao.getArtistSource(sourceId, remoteId)
        when {
            existing == null -> {
                mergeDao.insertArtistSource(ArtistSourceEntity(sourceId, remoteId, artistId, generation))
                syncDao.mergeCollectionFavorite(FavoriteKind.ARTIST, artistId, sourceId, remoteId, starredAt = null, isNewSource = true)
            }
            existing.seenInGeneration == generation -> Unit
            existing.artistId != artistId -> {
                mergeDao.updateArtistSource(existing.copy(artistId = artistId, seenInGeneration = generation))
                syncDao.mergeCollectionFavorite(FavoriteKind.ARTIST, artistId, sourceId, remoteId, existing.starredAt, isNewSource = true)
            }
            else -> mergeDao.updateArtistSource(existing.copy(seenInGeneration = generation))
        }
    }
}
