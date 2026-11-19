package de.kleinabersmart.earloft.core.data.mix

import android.util.Log
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.model.MixCandidateRow
import de.kleinabersmart.earloft.core.datasource.RemoteSimilarSongs
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What a mix starts from. */
sealed interface MixSeed {
    data class Song(val songId: Long) : MixSeed
    data class Album(val albumId: Long) : MixSeed
    data class Artist(val artistId: Long) : MixSeed
}

/**
 * Mix ("Play similar", concept 8): around [MIX_SIZE] songs from the user's own library that match the starting point.
 * It is based on the local database (genre, year, artist, listening history), offline and for local music. If a server
 * of the starting song is reachable, its suggestions are included (Subsonic `getSimilarSongs`, Jellyfin/Emby Instant Mix);
 * it gets at most [SERVER_TIMEOUT_MS] for this. Whatever the server suggests that is not in the library is dropped.
 */
@Singleton
class MixRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val libraryRepository: LibraryRepository,
    private val sources: MusicDataSources,
) {
    /**
     * Empty if the starting point doesn't exist (anymore). For song, album and artist the mix starts with one of their songs.
     * [offlineOnly]: without network only songs with a local file or finished download, without server suggestions ("Keep playing",
     * review V9); the starting song itself can still be first.
     */
    suspend fun mix(seed: MixSeed, random: Random = Random.Default, offlineOnly: Boolean = false): List<Song> {
        val seedIds = when (seed) {
            is MixSeed.Song -> listOf(seed.songId)
            is MixSeed.Album -> libraryDao.getSongIdsOfAlbum(seed.albumId)
            is MixSeed.Artist -> libraryDao.getSongRowsOfArtist(seed.artistId).map { it.songId }
        }.shuffled(random).take(SQLITE_MAX_VARIABLES)
        val seedRows = libraryDao.getMixRows(seedIds)
        if (seedRows.isEmpty()) return emptyList()
        val first = if (seed is MixSeed.Song) seedRows.first() else seedRows.random(random)
        val profile = MixProfile.of(seedRows, artistIds = (seed as? MixSeed.Artist)?.let { setOf(it.artistId) })

        val similar = if (offlineOnly) emptyList() else similarSongIds(first.songId)
        val candidates = LinkedHashMap<Long, MixCandidateRow>()
        if (offlineOnly) {
            // What plays without network is usually limited: all of it; the picker makes the selection by profile.
            libraryDao.getOfflineMixCandidates(CANDIDATE_LIMIT).associateByTo(candidates) { it.songId }
        } else {
            if (similar.isNotEmpty()) libraryDao.getMixRows(similar.take(SQLITE_MAX_VARIABLES)).associateByTo(candidates) { it.songId }
            val genres = seedRows.flatMap { it.genreKeys }.distinct().take(MAX_QUERY_GENRES)
            val year = profile.year
            libraryDao.getMixCandidates(
                genreKeys = genres,
                artistIds = profile.artistIds.take(MAX_QUERY_ARTISTS),
                fromYear = if (year == null) 1 else year - MixPicker.YEAR_SPAN,
                toYear = if (year == null) 0 else year + MixPicker.YEAR_SPAN,
                limit = CANDIDATE_LIMIT,
            ).forEach { candidates.putIfAbsent(it.songId, it) }
        }
        // Small or barely tagged libraries: fill up randomly so the mix doesn't end after three songs.
        if (!offlineOnly && candidates.size < MIX_SIZE * 2) {
            libraryDao.getRandomMixCandidates(MIX_SIZE * 3).forEach { candidates.putIfAbsent(it.songId, it) }
        }

        Log.d(TAG, "$seed: ${similar.size} vom Server, ${candidates.size} Kandidaten")
        val seedArtistLimit = if (seed is MixSeed.Artist) MIX_SIZE / 4 else MIX_SIZE / 10
        val ids = MixPicker.pick(
            profile = profile,
            candidates = candidates.values,
            similar = similar,
            first = first,
            size = MIX_SIZE,
            seedArtistLimit = seedArtistLimit,
            now = System.currentTimeMillis(),
            random = random,
        )
        return libraryRepository.songs(ids)
    }

    /** Suggestions of the song's first server that has any, as local song IDs; empty without a server or after the timeout. */
    private suspend fun similarSongIds(songId: Long): List<Long> = withTimeoutOrNull(SERVER_TIMEOUT_MS) {
        val serverSources = libraryDao.getPlayableSources(songId)
            .filter { it.sourceType.isServer }
            .sortedByDescending { it.sourcePriority }
        for (source in serverSources) {
            val provider = sources[source.sourceId] as? RemoteSimilarSongs ?: continue
            val remoteIds = try {
                provider.similarSongs(source.remoteId, SIMILAR_COUNT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Keine Vorschläge von ${source.sourceId}", e)
                continue
            }
            if (remoteIds.isEmpty()) continue
            val known = libraryDao.getSongIdsByRemoteId(source.sourceId, remoteIds.distinct().take(SQLITE_MAX_VARIABLES))
                .associate { it.remoteId to it.songId }
            val ids = remoteIds.mapNotNull { known[it] }.distinct().filter { it != songId }
            if (ids.isNotEmpty()) return@withTimeoutOrNull ids
        }
        emptyList()
    }.orEmpty()

    companion object {
        const val MIX_SIZE = 50
        private const val SIMILAR_COUNT = 100
        private const val CANDIDATE_LIMIT = 2000
        private const val MAX_QUERY_GENRES = 50
        private const val MAX_QUERY_ARTISTS = 200
        private const val SQLITE_MAX_VARIABLES = 900
        private const val SERVER_TIMEOUT_MS = 4_000L
        private const val TAG = "MixRepository"
    }
}
