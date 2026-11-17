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

/** Wovon ein Mix ausgeht. */
sealed interface MixSeed {
    data class Song(val songId: Long) : MixSeed
    data class Album(val albumId: Long) : MixSeed
    data class Artist(val artistId: Long) : MixSeed
}

/**
 * Mix („Ähnliches spielen“, Konzept 8): rund [MIX_SIZE] Songs aus der eigenen Bibliothek, die zum Ausgangspunkt passen.
 * Grundlage ist die lokale Datenbank (Genre, Jahr, Künstler, Hörverlauf), offline und für lokale Musik. Ist ein Server
 * des Ausgangs-Songs erreichbar, fließen seine Vorschläge ein (Subsonic `getSimilarSongs`, Jellyfin/Emby Instant Mix);
 * er bekommt dafür höchstens [SERVER_TIMEOUT_MS]. Was der Server vorschlägt, aber nicht in der Bibliothek ist, entfällt.
 */
@Singleton
class MixRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val libraryRepository: LibraryRepository,
    private val sources: MusicDataSources,
) {
    /**
     * Leer, wenn es den Ausgangspunkt nicht (mehr) gibt. Bei Song, Album und Künstler beginnt der Mix mit einem ihrer Songs.
     * [offlineOnly]: ohne Netz nur Songs mit lokaler Datei oder fertigem Download, ohne Vorschläge des Servers („Weiterspielen“,
     * Review V9); der Ausgangs-Song selbst kann trotzdem vorn stehen.
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
            // Was ohne Netz spielt, ist meist überschaubar: alles davon, die Auswahl trifft der Picker nach dem Profil.
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
        // Kleine oder kaum getaggte Bibliotheken: mit Zufall auffüllen, damit der Mix nicht nach drei Songs endet.
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

    /** Vorschläge des ersten Servers des Songs, der welche hat, als eigene Song-IDs; leer ohne Server oder nach Ablauf der Zeit. */
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
