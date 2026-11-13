package de.kleinabersmart.earloft.core.data.repository

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    fun genres(): Flow<List<Genre>> = libraryDao.observeGenreRows().map { rows -> rows.map { it.toGenre() } }

    fun albumsOfGenre(genre: String): Flow<List<Album>> =
        libraryDao.observeAlbumRowsOfGenre(genre).map { rows -> rows.map { it.toAlbum() } }

    suspend fun songsOfGenre(genre: String): List<Song> = libraryDao.getSongRowsOfGenre(genre).map { it.toSong() }

    fun decades(): Flow<List<Decade>> =
        libraryDao.observeDecadeRows().map { rows -> rows.map { Decade(it.startYear, it.albumCount) } }

    /** Je Album die Schlüssel seiner Genres ([Genre.key]); Alben ohne Genre fehlen. */
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

    /** Alben anderer Künstler, auf denen [artistId] mitwirkt. */
    fun appearsOn(artistId: Long): Flow<List<Album>> =
        libraryDao.observeAppearsOnAlbumRows(artistId).map { rows -> rows.map { it.toAlbum() } }

    /** Alle Songs des Künstlers: seine Alben und seine Songs auf anderen Alben, nach Jahr. */
    suspend fun songsOfArtist(artistId: Long): List<Song> = libraryDao.getSongRowsOfArtist(artistId).map { it.toSong() }

    fun topSongsOfArtist(artistId: Long, limit: Int = 5): Flow<List<Song>> =
        libraryDao.observeTopSongRowsOfArtist(artistId, limit).map { rows -> rows.map { it.toSong() } }

    /** Die Alben jedes Künstlers (Albumkünstler): Künstler-ID → Album-IDs. */
    fun albumIdsByArtist(): Flow<Map<Long, Set<Long>>> = libraryDao.observeAlbumArtists().map { refs ->
        refs.groupBy({ it.artistId }, { it.albumId }).mapValues { it.value.toSet() }
    }

    fun recentlyAddedAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRecentlyAddedAlbumRows(limit).map { rows -> rows.map { it.toAlbum() } }

    fun recentlyPlayedAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRecentlyPlayedAlbumRows(limit).map { rows -> rows.map { it.toAlbum() } }

    /**
     * Die in den letzten [periodMs] am häufigsten gehörten Alben; nur Alben mit mindestens [MOST_PLAYED_MIN_PLAYS] Wiedergaben,
     * damit die Zeile nicht nur „Zuletzt gehört“ wiederholt. Der Zeitraum gilt ab dem Abonnieren.
     */
    fun mostPlayedAlbums(periodMs: Long = MOST_PLAYED_PERIOD_MS, limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeMostPlayedAlbumRows(System.currentTimeMillis() - periodMs, MOST_PLAYED_MIN_PLAYS, limit)
            .map { rows -> rows.map { it.toAlbum() } }

    /** Gespielte Alben, die seit mindestens [notPlayedForMs] nicht mehr liefen; das älteste zuerst. */
    fun rediscoverAlbums(notPlayedForMs: Long = REDISCOVER_AFTER_MS, limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRediscoverAlbumRows(System.currentTimeMillis() - notPlayedForMs, limit)
            .map { rows -> rows.map { it.toAlbum() } }

    /**
     * Zufällige Alben. Die Auswahl bleibt stabil und wird nur neu gewürfelt, wenn sich die Zahl
     * der Alben ändert (z. B. nach dem ersten Scan) – nicht bei jeder Datenbankänderung.
     */
    fun randomAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeAlbumCount().distinctUntilChanged()
            .map { libraryDao.getRandomAlbumRows(limit).map { row -> row.toAlbum() } }

    /** Zufällige Künstler, Verhalten wie bei [randomAlbums]. */
    fun randomArtists(limit: Int = 20): Flow<List<Artist>> =
        libraryDao.observeAlbumCount().distinctUntilChanged()
            .map { libraryDao.getRandomArtistRows(limit).map { row -> row.toArtist() } }

    /** Liefert die Songs in der Reihenfolge von [songIds]; unbekannte IDs fallen weg. */
    suspend fun songs(songIds: List<Long>): List<Song> {
        val byId = songIds.distinct()
            .chunked(SQLITE_MAX_VARIABLES)
            .flatMap { libraryDao.getSongRows(it) }
            .associate { it.songId to it.toSong() }
        return songIds.mapNotNull { byId[it] }
    }

    /** Die Quellen eines Songs mit Format, Bitrate, Größe und ReplayGain (Titel-Info). */
    suspend fun sourcesOf(songId: Long): List<SongSourceInfo> = libraryDao.getSourceInfoRows(songId).map {
        SongSourceInfo(it.sourceId, it.sourceName, it.sourceType, it.mimeType, it.bitRate, it.sizeBytes, it.trackGainDb, it.albumGainDb, it.isDownloaded)
    }

    /** Welche der Song-Quellen (`sourceUid`) es noch gibt. */
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
     * Genres, deren Name jedes Wort der Eingabe enthält („hip hop“ findet „Hip-Hop“). Es gibt nur wenige Genres; deshalb
     * ohne Volltextindex.
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

        /** „Meistgehört“ (Regal auf Start, automatische Downloads): Zeitraum und Mindestzahl der Wiedergaben je Album. */
        const val MOST_PLAYED_PERIOD_MS = 90L * 24 * 60 * 60 * 1000
        const val MOST_PLAYED_MIN_PLAYS = 3
    }
}
