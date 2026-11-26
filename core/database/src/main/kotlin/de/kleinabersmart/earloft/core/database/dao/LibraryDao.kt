package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.model.AlbumGenreRow
import de.kleinabersmart.earloft.core.database.model.AlbumLookupRow
import de.kleinabersmart.earloft.core.database.model.AlbumRow
import de.kleinabersmart.earloft.core.database.model.ArtistRow
import de.kleinabersmart.earloft.core.database.model.DecadeRow
import de.kleinabersmart.earloft.core.database.model.GenreRow
import de.kleinabersmart.earloft.core.database.model.MixCandidateRow
import de.kleinabersmart.earloft.core.database.model.MusicPlayRow
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.database.model.RemoteSongIdRow
import de.kleinabersmart.earloft.core.database.model.ReplayGainRow
import de.kleinabersmart.earloft.core.database.model.SongAlbumRow
import de.kleinabersmart.earloft.core.database.model.SongRow
import de.kleinabersmart.earloft.core.database.model.SongSourceInfoRow
import de.kleinabersmart.earloft.core.database.model.TopPlayedRow
import kotlinx.coroutines.flow.Flow

/**
 * The song (alias `s`) has at least one source whose server is enabled. Disabled servers
 * hide their content without deleting anything (downloads, favorites, playlists are kept).
 */
const val SONG_VISIBLE = """EXISTS (
    SELECT 1 FROM song_sources vss JOIN sources vsrc ON vsrc.sourceId = vss.sourceId
    WHERE vss.songId = s.songId AND vsrc.enabled = 1
)"""

/** The song (alias `s`) is a file on the device (enabled local source). */
const val SONG_LOCAL = """EXISTS (
    SELECT 1 FROM song_sources lss JOIN sources lsrc ON lsrc.sourceId = lss.sourceId
    WHERE lss.songId = s.songId AND lsrc.type = 'LOCAL' AND lsrc.enabled = 1
)"""

private const val SELECT_SONG_ROWS = """
    SELECT s.songId, s.title, s.artistDisplay, s.albumId, a.title AS albumTitle,
           s.durationMs, s.discNumber, s.trackNumber, s.isFavorite
    FROM songs s JOIN albums a ON a.albumId = s.albumId AND $SONG_VISIBLE
"""

// DISTINCT: queries that join `play_events` would otherwise count plays instead of songs (review G44).
private const val ALBUM_COLUMNS = """
    a.albumId, a.title, a.albumArtistDisplay, a.year, COUNT(DISTINCT s.songId) AS songCount, a.seedColor,
    MAX(s.dateAdded) AS addedAt
"""

private const val ALBUM_FROM = "FROM albums a JOIN songs s ON s.albumId = a.albumId AND $SONG_VISIBLE"

// Without the time of the last play: reading `play_events` here would reload every album list on every play.
private const val SELECT_ALBUM_ROWS = "SELECT $ALBUM_COLUMNS, NULL AS lastPlayedAt $ALBUM_FROM"

private const val LAST_PLAYED_OF_ALBUM = """
    (SELECT MAX(pe.playedAt) FROM play_events pe JOIN songs ps ON ps.songId = pe.songId WHERE ps.albumId = a.albumId)
"""

private const val SELECT_ARTIST_ROWS = """
    SELECT ar.artistId, ar.name, COUNT(DISTINCT aa.albumId) AS albumCount
    FROM artists ar JOIN album_artists aa ON aa.artistId = ar.artistId
        AND EXISTS (SELECT 1 FROM songs s WHERE s.albumId = aa.albumId AND $SONG_VISIBLE)
"""

/**
 * Genres of the visible songs (`song_genres`, a song can have several); spellings that differ only in
 * case are one (`genreKey`).
 */
private const val SELECT_GENRE_ROWS = """
    SELECT MIN(g.genre) AS name, COUNT(DISTINCT s.albumId) AS albumCount, COUNT(*) AS songCount
    FROM song_genres g JOIN songs s ON s.songId = g.songId AND $SONG_VISIBLE
    GROUP BY g.genreKey
"""

/** Plays of visible songs between `:from` (inclusive) and `:to` (exclusive), with song (`s`) and album (`a`). */
private const val FROM_PLAYS_IN_RANGE = """
    FROM play_events pe
    JOIN songs s ON s.songId = pe.songId AND pe.playedAt >= :from AND pe.playedAt < :to AND $SONG_VISIBLE
    JOIN albums a ON a.albumId = s.albumId
"""

/** Features of visible songs for the mix (`MixCandidateRow`); conditions are appended at the end. */
private const val SELECT_MIX_CANDIDATES = """
    SELECT s.songId, s.albumId,
           COALESCE(
               (SELECT sa.artistId FROM song_artists sa WHERE sa.songId = s.songId ORDER BY sa.position LIMIT 1),
               (SELECT aa.artistId FROM album_artists aa WHERE aa.albumId = s.albumId ORDER BY aa.position LIMIT 1)
           ) AS artistId,
           (SELECT GROUP_CONCAT(g.genre, char(31)) FROM song_genres g WHERE g.songId = s.songId) AS genres,
           COALESCE(s.year, a.year) AS year, s.isFavorite,
           (SELECT COUNT(*) FROM play_events pe WHERE pe.songId = s.songId) AS playCount,
           (SELECT MAX(pe.playedAt) FROM play_events pe WHERE pe.songId = s.songId) AS lastPlayedAt
    FROM songs s JOIN albums a ON a.albumId = s.albumId AND $SONG_VISIBLE
"""

/** Read access to the library for UI and playback. */
@Dao
abstract class LibraryDao {

    // --- Lists for the UI ---

    @Query("$SELECT_ALBUM_ROWS GROUP BY a.albumId ORDER BY a.sortTitle COLLATE NOCASE")
    abstract fun observeAlbumRows(): Flow<List<AlbumRow>>

    @Query("$SELECT_ALBUM_ROWS WHERE a.albumId = :albumId GROUP BY a.albumId")
    abstract fun observeAlbumRow(albumId: Long): Flow<AlbumRow?>

    @Query("$SELECT_ARTIST_ROWS GROUP BY ar.artistId ORDER BY ar.sortName COLLATE NOCASE")
    abstract fun observeArtistRows(): Flow<List<ArtistRow>>

    @Query("$SELECT_ARTIST_ROWS WHERE ar.artistId = :artistId GROUP BY ar.artistId")
    abstract fun observeArtistRow(artistId: Long): Flow<ArtistRow?>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN album_artists aa ON aa.albumId = a.albumId
        WHERE aa.artistId = :artistId
        GROUP BY a.albumId
        ORDER BY a.year IS NULL, a.year, a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAlbumRowsOfArtist(artistId: Long): Flow<List<AlbumRow>>

    /**
     * Albums in the order [sort] (name of an `AlbumSort` except `RECENTLY_PLAYED`, see
     * [observeAlbumRowsByLastPlayed]): `ARTIST` by the first album artist (without "The"), then year; `YEAR` newest
     * first; `RECENTLY_ADDED` most recently added first; otherwise and on a tie by title.
     */
    @Query(
        """
        $SELECT_ALBUM_ROWS
        GROUP BY a.albumId
        ORDER BY
            CASE WHEN :sort = 'ARTIST' THEN COALESCE(
                (SELECT ar.sortName FROM album_artists aa JOIN artists ar ON ar.artistId = aa.artistId
                 WHERE aa.albumId = a.albumId ORDER BY aa.position LIMIT 1),
                a.albumArtistDisplay) END COLLATE NOCASE,
            CASE WHEN :sort = 'ARTIST' THEN a.year END,
            CASE WHEN :sort = 'YEAR' THEN a.year END DESC,
            CASE WHEN :sort = 'RECENTLY_ADDED' THEN MAX(s.dateAdded) END DESC,
            a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAlbumRowsSorted(sort: String): Flow<List<AlbumRow>>

    /** Albums, most recently played first; never-played ones after that by title. */
    @Query(
        """
        SELECT $ALBUM_COLUMNS, $LAST_PLAYED_OF_ALBUM AS lastPlayedAt $ALBUM_FROM
        GROUP BY a.albumId
        ORDER BY lastPlayedAt DESC, a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAlbumRowsByLastPlayed(): Flow<List<AlbumRow>>

    /** Artists in the order [sort] (name of an `ArtistSort`): `ALBUM_COUNT` those with the most albums first. */
    @Query(
        """
        $SELECT_ARTIST_ROWS
        GROUP BY ar.artistId
        ORDER BY CASE WHEN :sort = 'ALBUM_COUNT' THEN albumCount END DESC, ar.sortName COLLATE NOCASE
        """,
    )
    abstract fun observeArtistRowsSorted(sort: String): Flow<List<ArtistRow>>

    /**
     * Songs in the order [sort] (name of a `SongSort`): `ARTIST` by the first artist (without "The"), within that like
     * `ALBUM` album by album in album order; `RECENTLY_ADDED` most recently added first; otherwise by title.
     */
    @Query(
        """
        $SELECT_SONG_ROWS
        ORDER BY
            CASE WHEN :sort = 'ARTIST' THEN COALESCE(
                (SELECT ar.sortName FROM song_artists sa JOIN artists ar ON ar.artistId = sa.artistId
                 WHERE sa.songId = s.songId ORDER BY sa.position LIMIT 1),
                s.artistDisplay) END COLLATE NOCASE,
            CASE WHEN :sort IN ('ARTIST', 'ALBUM') THEN a.sortTitle END COLLATE NOCASE,
            CASE WHEN :sort IN ('ARTIST', 'ALBUM') THEN a.albumId END,
            CASE WHEN :sort IN ('ARTIST', 'ALBUM') THEN s.discNumber END,
            CASE WHEN :sort IN ('ARTIST', 'ALBUM') THEN s.trackNumber END,
            CASE WHEN :sort = 'RECENTLY_ADDED' THEN s.dateAdded END DESC,
            s.normTitle,
            s.title COLLATE NOCASE
        """,
    )
    abstract fun observeSongRowsSorted(sort: String): Flow<List<SongRow>>

    @Query("$SELECT_GENRE_ROWS ORDER BY name COLLATE NOCASE")
    abstract fun observeGenreRows(): Flow<List<GenreRow>>

    @Query("$SELECT_GENRE_ROWS ORDER BY name COLLATE NOCASE")
    abstract suspend fun getGenreRows(): List<GenreRow>

    /** Albums with at least one song of the genre ([genreKey] = `Genre.key`); `songCount` counts all visible songs of the album. */
    @Query(
        """
        $SELECT_ALBUM_ROWS
        WHERE EXISTS (
            SELECT 1 FROM songs gs JOIN song_genres g ON g.songId = gs.songId
            WHERE gs.albumId = a.albumId AND g.genreKey = :genreKey
        )
        GROUP BY a.albumId
        ORDER BY a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAlbumRowsOfGenre(genreKey: String): Flow<List<AlbumRow>>

    /** The songs of the genre ([genreKey] = `Genre.key`), album by album in album order. */
    @Query(
        """
        $SELECT_SONG_ROWS
        WHERE EXISTS (SELECT 1 FROM song_genres g WHERE g.songId = s.songId AND g.genreKey = :genreKey)
        ORDER BY a.sortTitle COLLATE NOCASE, a.albumId, s.discNumber, s.trackNumber, s.title
        """,
    )
    abstract suspend fun getSongRowsOfGenre(genreKey: String): List<SongRow>

    /** Decades (`startYear` = 1970, 1980, …) with the number of their visible albums; albums without a plausible year are missing. */
    @Query(
        """
        SELECT (a.year / 10) * 10 AS startYear, COUNT(*) AS albumCount
        FROM albums a
        WHERE a.year >= 1000 AND EXISTS (SELECT 1 FROM songs s WHERE s.albumId = a.albumId AND $SONG_VISIBLE)
        GROUP BY startYear
        ORDER BY startYear
        """,
    )
    abstract fun observeDecadeRows(): Flow<List<DecadeRow>>

    /**
     * Per album its genres (all genres of its songs, spellings as there), for the library's genre selection; albums
     * without a genre are missing.
     */
    @Query(
        """
        SELECT DISTINCT s.albumId, g.genre FROM songs s JOIN song_genres g ON g.songId = s.songId
        WHERE $SONG_VISIBLE
        """,
    )
    abstract fun observeAlbumGenreRows(): Flow<List<AlbumGenreRow>>

    /**
     * Albums on which [artistId] sings or plays at least one song without being the album artist (guests, compilations),
     * by year.
     */
    @Query(
        """
        $SELECT_ALBUM_ROWS
        WHERE EXISTS (
            SELECT 1 FROM songs xs JOIN song_artists sa ON sa.songId = xs.songId
            WHERE xs.albumId = a.albumId AND sa.artistId = :artistId
        )
        AND NOT EXISTS (SELECT 1 FROM album_artists aa WHERE aa.albumId = a.albumId AND aa.artistId = :artistId)
        GROUP BY a.albumId
        ORDER BY a.year IS NULL, a.year, a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAppearsOnAlbumRows(artistId: Long): Flow<List<AlbumRow>>

    /**
     * All songs of an artist: all of their albums and, from other albums, the songs they sing or play; album by album
     * by year, within that in album order.
     */
    @Query(
        """
        $SELECT_SONG_ROWS
        WHERE EXISTS (SELECT 1 FROM album_artists aa WHERE aa.albumId = s.albumId AND aa.artistId = :artistId)
           OR EXISTS (SELECT 1 FROM song_artists sa WHERE sa.songId = s.songId AND sa.artistId = :artistId)
        ORDER BY a.year IS NULL, a.year, a.sortTitle COLLATE NOCASE, a.albumId, s.discNumber, s.trackNumber, s.title
        """,
    )
    abstract suspend fun getSongRowsOfArtist(artistId: Long): List<SongRow>

    /** An artist's most played songs (like [getSongRowsOfArtist]); never-played ones are missing. */
    @Query(
        """
        $SELECT_SONG_ROWS
        JOIN play_events pe ON pe.songId = s.songId
        WHERE EXISTS (SELECT 1 FROM album_artists aa WHERE aa.albumId = s.albumId AND aa.artistId = :artistId)
           OR EXISTS (SELECT 1 FROM song_artists sa WHERE sa.songId = s.songId AND sa.artistId = :artistId)
        GROUP BY s.songId
        ORDER BY COUNT(*) DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeTopSongRowsOfArtist(artistId: Long, limit: Int): Flow<List<SongRow>>

    // --- Mix ---

    /** Mix features of the songs [songIds] (at most ~900, SQLite variable limit). */
    @Query("$SELECT_MIX_CANDIDATES WHERE s.songId IN (:songIds)")
    abstract suspend fun getMixRows(songIds: List<Long>): List<MixCandidateRow>

    /**
     * Up to [limit] random songs that could match a mix's starting point: one of the genres [genreKeys]
     * (`Genre.key`), one of the artists [artistIds] (as song or album artist) or a year between [fromYear] and
     * [toYear]. Without a year, pass `fromYear > toYear`.
     */
    @Query(
        """
        $SELECT_MIX_CANDIDATES
        WHERE EXISTS (SELECT 1 FROM song_genres g WHERE g.songId = s.songId AND g.genreKey IN (:genreKeys))
           OR EXISTS (SELECT 1 FROM song_artists sa WHERE sa.songId = s.songId AND sa.artistId IN (:artistIds))
           OR EXISTS (SELECT 1 FROM album_artists aa WHERE aa.albumId = s.albumId AND aa.artistId IN (:artistIds))
           OR COALESCE(s.year, a.year) BETWEEN :fromYear AND :toYear
        ORDER BY RANDOM()
        LIMIT :limit
        """,
    )
    abstract suspend fun getMixCandidates(
        genreKeys: List<String>,
        artistIds: List<Long>,
        fromYear: Int,
        toYear: Int,
        limit: Int,
    ): List<MixCandidateRow>

    /** Up to [limit] random visible songs to fill up a mix. */
    @Query("$SELECT_MIX_CANDIDATES ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomMixCandidates(limit: Int): List<MixCandidateRow>

    /**
     * Up to [limit] random visible songs that play without network: local file or finished download ("Keep playing"
     * offline, review V9).
     */
    @Query(
        """
        $SELECT_MIX_CANDIDATES
        WHERE $SONG_LOCAL
           OR EXISTS (SELECT 1 FROM downloads d WHERE d.songId = s.songId AND d.state = 'DONE')
        ORDER BY RANDOM()
        LIMIT :limit
        """,
    )
    abstract suspend fun getOfflineMixCandidates(limit: Int): List<MixCandidateRow>

    // --- Statistics ---

    /** All counted plays of visible songs (music statistics). */
    @Query("SELECT pe.songId, s.albumId, pe.playedAt, pe.listenedMs FROM play_events pe JOIN songs s ON s.songId = pe.songId WHERE $SONG_VISIBLE")
    abstract fun observeMusicPlays(): Flow<List<MusicPlayRow>>

    /** The most played songs between [from] (inclusive) and [to] (exclusive); on a tie the most recently played. */
    @Query(
        """
        SELECT s.songId AS id, s.title, s.artistDisplay AS subtitle, s.albumId, COUNT(*) AS plays, SUM(pe.listenedMs) AS listenedMs
        $FROM_PLAYS_IN_RANGE
        GROUP BY s.songId
        ORDER BY plays DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeTopSongs(from: Long, to: Long, limit: Int): Flow<List<TopPlayedRow>>

    /** Like [observeTopSongs], per album. */
    @Query(
        """
        SELECT a.albumId AS id, a.title, a.albumArtistDisplay AS subtitle, a.albumId, COUNT(*) AS plays, SUM(pe.listenedMs) AS listenedMs
        $FROM_PLAYS_IN_RANGE
        GROUP BY a.albumId
        ORDER BY plays DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeTopAlbums(from: Long, to: Long, limit: Int): Flow<List<TopPlayedRow>>

    /** Like [observeTopSongs], per artist: the song's first one, otherwise the album's first (as in the mix). */
    @Query(
        """
        SELECT ar.artistId AS id, ar.name AS title, NULL AS subtitle, NULL AS albumId, COUNT(*) AS plays, SUM(pe.listenedMs) AS listenedMs
        $FROM_PLAYS_IN_RANGE
        JOIN artists ar ON ar.artistId = COALESCE(
            (SELECT sa.artistId FROM song_artists sa WHERE sa.songId = s.songId ORDER BY sa.position LIMIT 1),
            (SELECT aa.artistId FROM album_artists aa WHERE aa.albumId = s.albumId ORDER BY aa.position LIMIT 1)
        )
        GROUP BY ar.artistId
        ORDER BY plays DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeTopArtists(from: Long, to: Long, limit: Int): Flow<List<TopPlayedRow>>

    /**
     * Like [observeTopSongs], per genre (spellings merged as in the "Genres" tab); a play counts for every
     * genre of the song.
     */
    @Query(
        """
        SELECT NULL AS id, MIN(g.genre) AS title, NULL AS subtitle, NULL AS albumId, COUNT(*) AS plays, SUM(pe.listenedMs) AS listenedMs
        $FROM_PLAYS_IN_RANGE
        JOIN song_genres g ON g.songId = s.songId
        GROUP BY g.genreKey
        ORDER BY plays DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeTopGenres(from: Long, to: Long, limit: Int): Flow<List<TopPlayedRow>>

    /** The songs the source [sourceId] carries under [remoteIds] (at most ~900). */
    @Query("SELECT remoteId, songId FROM song_sources WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    abstract suspend fun getSongIdsByRemoteId(sourceId: String, remoteIds: List<String>): List<RemoteSongIdRow>

    /** Which artists belong to which album (album artists); the watch derives its artists from this. */
    @Query("SELECT * FROM album_artists")
    abstract fun observeAlbumArtists(): Flow<List<AlbumArtistCrossRef>>

    // normTitle: without case and accents (review G14).
    @Query("$SELECT_SONG_ROWS ORDER BY s.normTitle, s.title COLLATE NOCASE")
    abstract fun observeSongRows(): Flow<List<SongRow>>

    @Query("$SELECT_SONG_ROWS WHERE s.albumId = :albumId ORDER BY s.discNumber, s.trackNumber, s.title")
    abstract fun observeSongRowsOfAlbum(albumId: Long): Flow<List<SongRow>>

    /** The songs of several albums, per album in album order; max. ~900 IDs per call (SQLite variable limit). */
    @Query("$SELECT_SONG_ROWS WHERE s.albumId IN (:albumIds) ORDER BY s.albumId, s.discNumber, s.trackNumber, s.title")
    abstract suspend fun getSongRowsOfAlbums(albumIds: List<Long>): List<SongRow>

    @Query("$SELECT_SONG_ROWS WHERE s.isFavorite ORDER BY s.favoriteChangedAt DESC")
    abstract fun observeFavoriteSongRows(): Flow<List<SongRow>>

    @Query("SELECT isFavorite FROM songs WHERE songId = :songId")
    abstract fun observeIsFavorite(songId: Long): Flow<Boolean?>

    @Query("$SELECT_ALBUM_ROWS WHERE a.isFavorite GROUP BY a.albumId ORDER BY a.favoriteChangedAt DESC")
    abstract fun observeFavoriteAlbumRows(): Flow<List<AlbumRow>>

    @Query("$SELECT_ARTIST_ROWS WHERE ar.isFavorite GROUP BY ar.artistId ORDER BY ar.favoriteChangedAt DESC")
    abstract fun observeFavoriteArtistRows(): Flow<List<ArtistRow>>

    @Query("SELECT songId FROM songs WHERE isFavorite AND songId IN (:songIds)")
    abstract fun observeFavoriteSongIdsAmong(songIds: List<Long>): Flow<List<Long>>

    @Query("SELECT albumId FROM albums WHERE isFavorite")
    abstract fun observeFavoriteAlbumIds(): Flow<List<Long>>

    @Query("SELECT artistId FROM artists WHERE isFavorite")
    abstract fun observeFavoriteArtistIds(): Flow<List<Long>>

    @Query("SELECT isFavorite FROM albums WHERE albumId = :albumId")
    abstract fun observeIsAlbumFavorite(albumId: Long): Flow<Boolean?>

    @Query("SELECT isFavorite FROM artists WHERE artistId = :artistId")
    abstract fun observeIsArtistFavorite(artistId: Long): Flow<Boolean?>

    /** Order does not match [songIds]; max. ~900 IDs per call (SQLite variable limit). */
    @Query("$SELECT_SONG_ROWS WHERE s.songId IN (:songIds)")
    abstract suspend fun getSongRows(songIds: List<Long>): List<SongRow>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        GROUP BY a.albumId
        ORDER BY MAX(s.dateAdded) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecentlyAddedAlbumRows(limit: Int): Flow<List<AlbumRow>>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN play_events pe ON pe.songId = s.songId
        GROUP BY a.albumId
        ORDER BY MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecentlyPlayedAlbumRows(limit: Int): Flow<List<AlbumRow>>

    /**
     * The albums played most often since [since] (at least [minPlays] counted plays), on a tie the most recently
     * played first. `songCount` counts the visible songs of the album (as on the album page), not the plays.
     */
    @Query(
        """
        SELECT a.albumId, a.title, a.albumArtistDisplay, a.year,
               (SELECT COUNT(*) FROM songs s WHERE s.albumId = a.albumId AND $SONG_VISIBLE) AS songCount
        FROM albums a
        JOIN songs s ON s.albumId = a.albumId AND $SONG_VISIBLE
        JOIN play_events pe ON pe.songId = s.songId AND pe.playedAt >= :since
        GROUP BY a.albumId
        HAVING COUNT(*) >= :minPlays
        ORDER BY COUNT(*) DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeMostPlayedAlbumRows(since: Long, minPlays: Int, limit: Int): Flow<List<AlbumRow>>

    /** Albums that have been played before, but last before [playedBefore]; oldest first. */
    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN play_events pe ON pe.songId = s.songId
        GROUP BY a.albumId
        HAVING MAX(pe.playedAt) < :playedBefore
        ORDER BY MAX(pe.playedAt)
        LIMIT :limit
        """,
    )
    abstract fun observeRediscoverAlbumRows(playedBefore: Long, limit: Int): Flow<List<AlbumRow>>

    @Query("SELECT COUNT(DISTINCT s.albumId) FROM songs s WHERE $SONG_VISIBLE")
    abstract fun observeAlbumCount(): Flow<Int>

    /** Highest assigned `songId` (0 without songs); IDs are never reused, so later songs have a larger one. */
    @Query("SELECT COALESCE(MAX(songId), 0) FROM songs")
    abstract suspend fun maxSongId(): Long

    /** Visible songs created after [songId]. */
    @Query("SELECT COUNT(*) FROM songs s WHERE s.songId > :songId AND $SONG_VISIBLE")
    abstract suspend fun countVisibleSongsAfter(songId: Long): Int

    /** Albums with visible songs created after [songId]; `songCount` counts only those, most first. */
    @Query("$SELECT_ALBUM_ROWS AND s.songId > :songId GROUP BY a.albumId ORDER BY songCount DESC, a.sortTitle")
    abstract suspend fun albumsWithSongsAfter(songId: Long): List<AlbumRow>

    @Query("$SELECT_ALBUM_ROWS GROUP BY a.albumId ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomAlbumRows(limit: Int): List<AlbumRow>

    @Query("$SELECT_ARTIST_ROWS GROUP BY ar.artistId ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomArtistRows(limit: Int): List<ArtistRow>

    // --- Full-text search (FTS4 MATCH expression, e.g. "sig* ros*") ---

    @Query(
        """
        $SELECT_SONG_ROWS
        JOIN songs_fts ON songs_fts.rowid = s.songId
        WHERE songs_fts MATCH :match
        ORDER BY s.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    abstract suspend fun searchSongRows(match: String, limit: Int): List<SongRow>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN albums_fts ON albums_fts.rowid = a.albumId
        WHERE albums_fts MATCH :match
        GROUP BY a.albumId
        ORDER BY a.sortTitle COLLATE NOCASE
        LIMIT :limit
        """,
    )
    abstract suspend fun searchAlbumRows(match: String, limit: Int): List<AlbumRow>

    @Query(
        """
        $SELECT_ARTIST_ROWS
        JOIN artists_fts ON artists_fts.rowid = ar.artistId
        WHERE artists_fts MATCH :match
        GROUP BY ar.artistId
        ORDER BY ar.sortName COLLATE NOCASE
        LIMIT :limit
        """,
    )
    abstract suspend fun searchArtistRows(match: String, limit: Int): List<ArtistRow>

    // --- Playback & artwork ---

    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate
        FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.enabled = 1
        """,
    )
    abstract suspend fun getPlayableSources(songId: Long): List<PlayableSourceRow>

    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate
        FROM song_sources ss
        JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId IN (:songIds) AND src.enabled = 1
        """,
    )
    abstract suspend fun getPlayableSourcesOfSongs(songIds: List<Long>): List<PlayableSourceRow>

    /** The artists of an album in their order (for "go to artist"); album count 0, not needed there. */
    @Query(
        """
        SELECT ar.artistId, ar.name, 0 AS albumCount FROM album_artists aa
        JOIN artists ar ON ar.artistId = aa.artistId
        WHERE aa.albumId = :albumId ORDER BY aa.position
        """,
    )
    abstract suspend fun getArtistRowsOfAlbum(albumId: Long): List<ArtistRow>

    /**
     * The artists of a song that have an artist page (are album artists somewhere), in their order. A guest on a compilation
     * without an own album has none.
     */
    @Query(
        """
        SELECT ar.artistId, ar.name, 0 AS albumCount FROM song_artists sa
        JOIN artists ar ON ar.artistId = sa.artistId
        WHERE sa.songId = :songId AND EXISTS (SELECT 1 FROM album_artists aa WHERE aa.artistId = sa.artistId)
        ORDER BY sa.position
        """,
    )
    abstract suspend fun getPageArtistRowsOfSong(songId: Long): List<ArtistRow>

    @Query("SELECT songId, albumId FROM songs WHERE songId IN (:songIds)")
    abstract suspend fun getAlbumIdsOfSongs(songIds: List<Long>): List<SongAlbumRow>

    /** The enabled sources of a song, local ones first, then by priority (track info). */
    @Query(
        """
        SELECT ss.sourceId, src.name AS sourceName, src.type AS sourceType, ss.mimeType, ss.bitRate, ss.sizeBytes,
               ss.rgTrackGainDb AS trackGainDb, ss.rgAlbumGainDb AS albumGainDb,
               EXISTS(SELECT 1 FROM downloads d WHERE d.sourceUid = ss.uid AND d.state = 'DONE') AS isDownloaded
        FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.enabled = 1
        ORDER BY src.type = 'LOCAL' DESC, src.priority DESC, src.name
        """,
    )
    abstract suspend fun getSourceInfoRows(songId: Long): List<SongSourceInfoRow>

    @Query("SELECT rgTrackGainDb, rgTrackPeak, rgAlbumGainDb, rgAlbumPeak FROM song_sources WHERE uid = :uid")
    abstract suspend fun getReplayGain(uid: String): ReplayGainRow?

    @Query("SELECT uid FROM song_sources WHERE uid IN (:uids)")
    abstract suspend fun getExistingSourceUids(uids: List<String>): List<String>

    @Query("SELECT songId FROM songs WHERE albumId = :albumId")
    abstract suspend fun getSongIdsOfAlbum(albumId: Long): List<Long>

    /** Prefers the album's cover source, otherwise any source of an album song. */
    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate
        FROM song_sources ss
        JOIN songs s ON s.songId = ss.songId
        JOIN sources src ON src.sourceId = ss.sourceId
        WHERE s.albumId = :albumId
        ORDER BY ss.uid = (SELECT coverSourceUid FROM albums WHERE albumId = :albumId) DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getCoverSource(albumId: Long): PlayableSourceRow?

    @Query("SELECT albumId FROM albums")
    abstract suspend fun getAllAlbumIds(): List<Long>

    @Query("SELECT artistId FROM artists")
    abstract suspend fun getAllArtistIds(): List<Long>

    /** Changes as soon as albums or artists are added or removed (reason to load their images for offline use). */
    @Query(
        "SELECT (SELECT COUNT(*) || ':' || IFNULL(MAX(albumId), 0) FROM albums) || '/' || " +
            "(SELECT COUNT(*) || ':' || IFNULL(MAX(artistId), 0) FROM artists)",
    )
    abstract fun observeImageStamp(): Flow<String>

    @Query("SELECT albumId, title, albumArtistDisplay AS artist, mbid FROM albums WHERE albumId = :albumId")
    abstract suspend fun getAlbumLookup(albumId: Long): AlbumLookupRow?

    @Query("SELECT name FROM artists WHERE artistId = :artistId")
    abstract suspend fun getArtistName(artistId: Long): String?

    @Query("SELECT * FROM artists WHERE artistId = :artistId")
    abstract suspend fun getArtist(artistId: Long): ArtistEntity?

    @Query("SELECT * FROM songs WHERE songId = :songId")
    abstract suspend fun getSongEntity(songId: Long): SongEntity?

    @Query("SELECT seedColor FROM albums WHERE albumId = :albumId")
    abstract fun observeSeedColor(albumId: Long): Flow<Int?>

    @Query("UPDATE albums SET seedColor = :color WHERE albumId = :albumId")
    abstract suspend fun setSeedColor(albumId: Long, color: Int)

    /** Several colors in one write, so album lists reload once instead of once per album. */
    @Transaction
    open suspend fun setSeedColors(colors: Map<Long, Int>) {
        colors.forEach { (albumId, color) -> setSeedColor(albumId, color) }
    }

    /** Visible albums whose cover color is not computed yet, in title order. */
    @Query(
        """
        SELECT a.albumId FROM albums a
        WHERE a.seedColor IS NULL AND EXISTS (SELECT 1 FROM songs s WHERE s.albumId = a.albumId AND $SONG_VISIBLE)
        ORDER BY a.sortTitle COLLATE NOCASE
        """,
    )
    abstract suspend fun getAlbumIdsWithoutSeedColor(): List<Long>

    // --- Cleanup ---

    @Query("DELETE FROM songs WHERE songId NOT IN (SELECT songId FROM song_sources)")
    protected abstract suspend fun deleteSongsWithoutSources(): Int

    @Query("DELETE FROM albums WHERE albumId NOT IN (SELECT albumId FROM songs)")
    protected abstract suspend fun deleteAlbumsWithoutSongs(): Int

    @Query(
        """
        DELETE FROM artists
        WHERE artistId NOT IN (SELECT artistId FROM song_artists)
          AND artistId NOT IN (SELECT artistId FROM album_artists)
        """,
    )
    protected abstract suspend fun deleteUnreferencedArtists(): Int

    @Query(
        """
        UPDATE albums SET coverSourceUid = (
            SELECT ss.uid FROM song_sources ss JOIN songs s ON s.songId = ss.songId
            WHERE s.albumId = albums.albumId LIMIT 1
        )
        WHERE coverSourceUid IS NULL OR coverSourceUid NOT IN (SELECT uid FROM song_sources)
        """,
    )
    protected abstract suspend fun repairAlbumCovers()

    /**
     * Cleans up after sources are removed or after a mark-and-sweep sync:
     * songs without a source, then empty albums, artists no longer referenced and
     * cover references to deleted sources.
     */
    @Transaction
    open suspend fun cleanupOrphans() {
        deleteSongsWithoutSources()
        deleteAlbumsWithoutSongs()
        deleteUnreferencedArtists()
        repairAlbumCovers()
    }
}
