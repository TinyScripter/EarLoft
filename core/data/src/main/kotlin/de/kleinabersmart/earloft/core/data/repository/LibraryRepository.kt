package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.common.foldedSortKey
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumDetails
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.ArtistSort
import de.kleinabersmart.earloft.core.model.SongSort
import de.kleinabersmart.earloft.core.model.SongSourceInfo
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.ArtistDetails
import de.kleinabersmart.earloft.core.model.Decade
import de.kleinabersmart.earloft.core.model.Genre
import de.kleinabersmart.earloft.core.model.SearchResults
import de.kleinabersmart.earloft.core.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
) {
    fun albums(): Flow<List<Album>> = libraryDao.observeAlbumRows().map { rows -> rows.map { it.toAlbum() } }

    fun artists(): Flow<List<Artist>> = libraryDao.observeArtistRows().map { rows -> rows.map { it.toArtist() } }

    fun songs(): Flow<List<Song>> = libraryDao.observeSongRows().map { rows -> rows.map { it.toSong() } }

    fun albums(sort: AlbumSort): Flow<List<Album>> = when (sort) {
        AlbumSort.RECENTLY_PLAYED -> libraryDao.observeAlbumRowsByLastPlayed()
        else -> libraryDao.observeAlbumRowsSorted(sort.name)
    }.map { rows -> rows.map { it.toAlbum() } }

    fun artists(sort: ArtistSort): Flow<List<Artist>> =
        libraryDao.observeArtistRowsSorted(sort.name).map { rows -> rows.map { it.toArtist() } }

    fun songs(sort: SongSort): Flow<List<Song>> = libraryDao.observeSongRowsSorted(sort.name).map { rows -> rows.map { it.toSong() } }

    /** By name without case and accents (review G14). */
    fun genres(): Flow<List<Genre>> =
        libraryDao.observeGenreRows().map { rows -> rows.sortedBy { it.name.foldedSortKey() }.map { it.toGenre() } }

    /** Albums with songs of the genre [genre] (name in any spelling). */
    fun albumsOfGenre(genre: String): Flow<List<Album>> =
        libraryDao.observeAlbumRowsOfGenre(Genre.key(genre)).map { rows -> rows.map { it.toAlbum() } }

    suspend fun songsOfGenre(genre: String): List<Song> = libraryDao.getSongRowsOfGenre(Genre.key(genre)).map { it.toSong() }

    /** All songs of the [albumIds], album by album in their order, each album in album order. */
    suspend fun songsOfAlbums(albumIds: List<Long>): List<Song> {
        val byAlbum = albumIds.distinct().chunked(SQLITE_MAX_VARIABLES)
            .flatMap { libraryDao.getSongRowsOfAlbums(it) }
            .groupBy { it.albumId }
        return albumIds.distinct().flatMap { albumId -> byAlbum[albumId].orEmpty().map { it.toSong() } }
    }

    fun decades(): Flow<List<Decade>> =
        libraryDao.observeDecadeRows().map { rows -> rows.map { Decade(it.startYear, it.albumCount) } }

    /** Per album the keys of its genres ([Genre.key]); albums without a genre are missing. */
    fun albumGenreKeys(): Flow<Map<Long, Set<String>>> =
        libraryDao.observeAlbumGenreRows().map { rows ->
            rows.groupBy({ it.albumId }, { Genre.key(it.genre) }).mapValues { (_, keys) -> keys.toSet() }
        }

    fun album(albumId: Long): Flow<AlbumDetails?> =
        combine(libraryDao.observeAlbumRow(albumId), libraryDao.observeSongRowsOfAlbum(albumId)) { album, songs ->
            album?.let { AlbumDetails(it.toAlbum(), songs.map { row -> row.toSong() }) }
        }

    fun artist(artistId: Long): Flow<ArtistDetails?> =
        combine(libraryDao.observeArtistRow(artistId), libraryDao.observeAlbumRowsOfArtist(artistId)) { artist, albums ->
            artist?.let { ArtistDetails(it.toArtist(), albums.map { row -> row.toAlbum() }) }
        }

    /** Albums by other artists on which [artistId] appears. */
    fun appearsOn(artistId: Long): Flow<List<Album>> =
        libraryDao.observeAppearsOnAlbumRows(artistId).map { rows -> rows.map { it.toAlbum() } }

    /** All songs of the artist: their albums and their songs on other albums, by year. */
    /** The artists of an album, for "go to artist". */
    suspend fun artistsOfAlbum(albumId: Long): List<Artist> = libraryDao.getArtistRowsOfAlbum(albumId).map { it.toArtist() }

    /**
     * Where "go to artist" leads from a song: its own artists that have a page, otherwise the artists of its album (a
     * compilation guest leads to the album artist).
     */
    suspend fun artistsOfSong(songId: Long, albumId: Long?): List<Artist> =
        libraryDao.getPageArtistRowsOfSong(songId).ifEmpty { albumId?.let { libraryDao.getArtistRowsOfAlbum(it) }.orEmpty() }
            .map { it.toArtist() }

    suspend fun songsOfArtist(artistId: Long): List<Song> = libraryDao.getSongRowsOfArtist(artistId).map { it.toSong() }

    fun topSongsOfArtist(artistId: Long, limit: Int = 5): Flow<List<Song>> =
        libraryDao.observeTopSongRowsOfArtist(artistId, limit).map { rows -> rows.map { it.toSong() } }

    /** The albums of each artist (album artist): artist ID → album IDs. */
    fun albumIdsByArtist(): Flow<Map<Long, Set<Long>>> = libraryDao.observeAlbumArtists().map { refs ->
        refs.groupBy({ it.artistId }, { it.albumId }).mapValues { it.value.toSet() }
    }

    fun recentlyAddedAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRecentlyAddedAlbumRows(limit).map { rows -> rows.map { it.toAlbum() } }

    fun recentlyPlayedAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRecentlyPlayedAlbumRows(limit).map { rows -> rows.map { it.toAlbum() } }

    /**
     * The most played albums in the last [periodMs]; only albums with at least [MOST_PLAYED_MIN_PLAYS] plays,
     * so the row doesn't just repeat "Recently played". The period is recomputed hourly ([windowStarts]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun mostPlayedAlbums(periodMs: Long = MOST_PLAYED_PERIOD_MS, limit: Int = 20): Flow<List<Album>> =
        windowStarts(periodMs)
            .flatMapLatest { since -> libraryDao.observeMostPlayedAlbumRows(since, MOST_PLAYED_MIN_PLAYS, limit) }
            .map { rows -> rows.map { it.toAlbum() } }

    /** Played albums that haven't been played for at least [notPlayedForMs]; the oldest first. */
    fun rediscoverAlbums(notPlayedForMs: Long = REDISCOVER_AFTER_MS, limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRediscoverAlbumRows(System.currentTimeMillis() - notPlayedForMs, limit)
            .map { rows -> rows.map { it.toAlbum() } }

    /**
     * Random albums. The selection stays stable and is only reshuffled when the number
     * of albums changes (e.g. after the first scan) – not on every database change.
     */
    fun randomAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeAlbumCount().distinctUntilChanged()
            .map { libraryDao.getRandomAlbumRows(limit).map { row -> row.toAlbum() } }

    /** Random artists, behaving like [randomAlbums]. */
    fun randomArtists(limit: Int = 20): Flow<List<Artist>> =
        libraryDao.observeAlbumCount().distinctUntilChanged()
            .map { libraryDao.getRandomArtistRows(limit).map { row -> row.toArtist() } }

    /** Returns the songs in the order of [songIds]; unknown IDs are dropped. */
    suspend fun songs(songIds: List<Long>): List<Song> {
        val byId = songIds.distinct()
            .chunked(SQLITE_MAX_VARIABLES)
            .flatMap { libraryDao.getSongRows(it) }
            .associate { it.songId to it.toSong() }
        return songIds.mapNotNull { byId[it] }
    }

    /** The sources of a song with format, bitrate, size and ReplayGain (track info). */
    suspend fun sourcesOf(songId: Long): List<SongSourceInfo> = libraryDao.getSourceInfoRows(songId).map {
        SongSourceInfo(it.sourceId, it.sourceName, it.sourceType, it.mimeType, it.bitRate, it.sizeBytes, it.trackGainDb, it.albumGainDb, it.isDownloaded)
    }

    /** Which of the song sources (`sourceUid`) still exist. */
    suspend fun existingSourceUids(uids: Collection<String>): Set<String> =
        uids.chunked(SQLITE_MAX_VARIABLES).flatMapTo(HashSet()) { libraryDao.getExistingSourceUids(it) }

    suspend fun search(query: String, limit: Int = 50): SearchResults {
        val match = FtsQuery.build(query) ?: return SearchResults.EMPTY
        return SearchResults(
            songs = libraryDao.searchSongRows(match, limit).map { it.toSong() },
            albums = libraryDao.searchAlbumRows(match, limit).map { it.toAlbum() },
            artists = libraryDao.searchArtistRows(match, limit).map { it.toArtist() },
            genres = searchGenres(query, limit),
        )
    }

    /**
     * Genres whose name contains every word of the input ("hip hop" finds "Hip-Hop"). There are only a few genres; hence
     * without a full-text index.
     */
    private suspend fun searchGenres(query: String, limit: Int): List<Genre> {
        val terms = query.lowercase().split(SEARCH_SEPARATORS).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return libraryDao.getGenreRows()
            .filter { row -> row.name.lowercase().let { name -> terms.all { it in name } } }
            .take(limit)
            .map { it.toGenre() }
    }

    companion object {
        private const val SQLITE_MAX_VARIABLES = 900
        private val SEARCH_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
        private const val REDISCOVER_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        /** "Most played" (shelf on Home, automatic downloads): period and minimum number of plays per album. */
        const val MOST_PLAYED_PERIOD_MS = 90L * 24 * 60 * 60 * 1000
        const val MOST_PLAYED_MIN_PLAYS = 3

        /** How often the start of a running period ([windowStarts]) is recomputed. */
        const val WINDOW_REFRESH_MS = 60L * 60 * 1000

        /**
         * The start of a period from [periodMs] ago until now, recomputed every [refreshMs]: a process that
         * runs for days thus drops entries that have aged out of the period. The tick runs on the
         * real clock, even under a test scheduler: its skipped time would restart the query endlessly.
         */
        fun windowStarts(periodMs: Long, refreshMs: Long = WINDOW_REFRESH_MS): Flow<Long> = flow {
            while (true) {
                emit(System.currentTimeMillis() - periodMs)
                withContext(Dispatchers.Default) { delay(refreshMs) }
            }
        }
    }
}
