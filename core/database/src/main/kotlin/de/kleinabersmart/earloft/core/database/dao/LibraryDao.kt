package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
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
import de.kleinabersmart.earloft.core.database.model.SongRow
import de.kleinabersmart.earloft.core.database.model.SongSourceInfoRow
import de.kleinabersmart.earloft.core.database.model.TopPlayedRow
import kotlinx.coroutines.flow.Flow

/**
 * Der Song (Alias `s`) hat mindestens eine Quelle, deren Server eingeschaltet ist. Deaktivierte Server
 * blenden ihre Inhalte aus, ohne etwas zu löschen (Downloads, Favoriten, Playlists bleiben erhalten).
 */
const val SONG_VISIBLE = """EXISTS (
    SELECT 1 FROM song_sources vss JOIN sources vsrc ON vsrc.sourceId = vss.sourceId
    WHERE vss.songId = s.songId AND vsrc.enabled = 1
)"""

/** Der Song (Alias `s`) liegt als Datei auf dem Gerät (eingeschaltete lokale Quelle). */
const val SONG_LOCAL = """EXISTS (
    SELECT 1 FROM song_sources lss JOIN sources lsrc ON lsrc.sourceId = lss.sourceId
    WHERE lss.songId = s.songId AND lsrc.type = 'LOCAL' AND lsrc.enabled = 1
)"""

private const val SELECT_SONG_ROWS = """
    SELECT s.songId, s.title, s.artistDisplay, s.albumId, a.title AS albumTitle,
           s.durationMs, s.discNumber, s.trackNumber, s.isFavorite
    FROM songs s JOIN albums a ON a.albumId = s.albumId AND $SONG_VISIBLE
"""

private const val SELECT_ALBUM_ROWS = """
    SELECT a.albumId, a.title, a.albumArtistDisplay, a.year, COUNT(s.songId) AS songCount
    FROM albums a JOIN songs s ON s.albumId = a.albumId AND $SONG_VISIBLE
"""

private const val SELECT_ARTIST_ROWS = """
    SELECT ar.artistId, ar.name, COUNT(DISTINCT aa.albumId) AS albumCount
    FROM artists ar JOIN album_artists aa ON aa.artistId = ar.artistId
        AND EXISTS (SELECT 1 FROM songs s WHERE s.albumId = aa.albumId AND $SONG_VISIBLE)
"""

/**
 * Genres der sichtbaren Songs; Schreibweisen, die sich nur in Groß-/Kleinschreibung unterscheiden, sind eins. Ein Song hat
 * genau ein Genre (das erste, das die Quelle nennt).
 */
private const val SELECT_GENRE_ROWS = """
    SELECT MIN(s.genre) AS name, COUNT(DISTINCT s.albumId) AS albumCount, COUNT(*) AS songCount
    FROM songs s
    WHERE s.genre IS NOT NULL AND TRIM(s.genre) != '' AND $SONG_VISIBLE
    GROUP BY s.genre COLLATE NOCASE
"""

/** Wiedergaben sichtbarer Songs zwischen `:from` (einschließlich) und `:to` (ausschließlich), samt Song (`s`) und Album (`a`). */
private const val FROM_PLAYS_IN_RANGE = """
    FROM play_events pe
    JOIN songs s ON s.songId = pe.songId AND pe.playedAt >= :from AND pe.playedAt < :to AND $SONG_VISIBLE
    JOIN albums a ON a.albumId = s.albumId
"""

/** Merkmale sichtbarer Songs für den Mix (`MixCandidateRow`); Bedingungen hängen hinten an. */
private const val SELECT_MIX_CANDIDATES = """
    SELECT s.songId, s.albumId,
           COALESCE(
               (SELECT sa.artistId FROM song_artists sa WHERE sa.songId = s.songId ORDER BY sa.position LIMIT 1),
               (SELECT aa.artistId FROM album_artists aa WHERE aa.albumId = s.albumId ORDER BY aa.position LIMIT 1)
           ) AS artistId,
           s.genre, COALESCE(s.year, a.year) AS year, s.isFavorite,
           (SELECT COUNT(*) FROM play_events pe WHERE pe.songId = s.songId) AS playCount,
           (SELECT MAX(pe.playedAt) FROM play_events pe WHERE pe.songId = s.songId) AS lastPlayedAt
    FROM songs s JOIN albums a ON a.albumId = s.albumId AND $SONG_VISIBLE
"""

/** Lesender Zugriff auf die Bibliothek für UI und Wiedergabe. */
@Dao
abstract class LibraryDao {

    @Insert
    abstract suspend fun insertArtist(artist: ArtistEntity): Long

    @Insert
    abstract suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert
    abstract suspend fun insertSong(song: SongEntity): Long

    @Insert
    abstract suspend fun insertSongSource(songSource: SongSourceEntity)

    @Insert
    abstract suspend fun insertSongArtist(crossRef: SongArtistCrossRef)

    @Insert
    abstract suspend fun insertAlbumArtist(crossRef: AlbumArtistCrossRef)

    @Query("SELECT * FROM albums ORDER BY sortTitle")
    abstract fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artists ORDER BY sortName")
    abstract fun observeArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY discNumber, trackNumber, title")
    abstract fun observeSongsOfAlbum(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM song_sources WHERE songId = :songId")
    abstract suspend fun getSourcesOfSong(songId: Long): List<SongSourceEntity>

    // --- Listen für die UI ---

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
     * Alben in der Reihenfolge [sort] (Name eines `AlbumSort` außer `RECENTLY_PLAYED`, siehe
     * [observeAlbumRowsByLastPlayed]): `ARTIST` nach dem ersten Album-Künstler (ohne „The“), dann Jahr; `YEAR` das neueste
     * zuerst; `RECENTLY_ADDED` das zuletzt hinzugefügte zuerst; sonst und bei Gleichstand nach Titel.
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

    /** Alben, das zuletzt gehörte zuerst; nie gehörte danach nach Titel. */
    @Query(
        """
        $SELECT_ALBUM_ROWS
        GROUP BY a.albumId
        ORDER BY (SELECT MAX(pe.playedAt) FROM play_events pe JOIN songs ps ON ps.songId = pe.songId
                  WHERE ps.albumId = a.albumId) DESC,
            a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAlbumRowsByLastPlayed(): Flow<List<AlbumRow>>

    /** Künstler in der Reihenfolge [sort] (Name eines `ArtistSort`): `ALBUM_COUNT` die mit den meisten Alben zuerst. */
    @Query(
        """
        $SELECT_ARTIST_ROWS
        GROUP BY ar.artistId
        ORDER BY CASE WHEN :sort = 'ALBUM_COUNT' THEN albumCount END DESC, ar.sortName COLLATE NOCASE
        """,
    )
    abstract fun observeArtistRowsSorted(sort: String): Flow<List<ArtistRow>>

    /**
     * Songs in der Reihenfolge [sort] (Name eines `SongSort`): `ARTIST` nach dem ersten Künstler (ohne „The“), darin wie
     * `ALBUM` Album für Album in Albumreihenfolge; `RECENTLY_ADDED` der zuletzt hinzugefügte zuerst; sonst nach Titel.
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
            s.title COLLATE NOCASE
        """,
    )
    abstract fun observeSongRowsSorted(sort: String): Flow<List<SongRow>>

    @Query("$SELECT_GENRE_ROWS ORDER BY name COLLATE NOCASE")
    abstract fun observeGenreRows(): Flow<List<GenreRow>>

    @Query("$SELECT_GENRE_ROWS ORDER BY name COLLATE NOCASE")
    abstract suspend fun getGenreRows(): List<GenreRow>

    /** Alben mit mindestens einem Song des Genres; `songCount` zählt alle sichtbaren Songs des Albums. */
    @Query(
        """
        $SELECT_ALBUM_ROWS
        WHERE EXISTS (SELECT 1 FROM songs gs WHERE gs.albumId = a.albumId AND gs.genre = :genre COLLATE NOCASE)
        GROUP BY a.albumId
        ORDER BY a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAlbumRowsOfGenre(genre: String): Flow<List<AlbumRow>>

    /** Die Songs des Genres, Album für Album in Albumreihenfolge. */
    @Query(
        """
        $SELECT_SONG_ROWS
        WHERE s.genre = :genre COLLATE NOCASE
        ORDER BY a.sortTitle COLLATE NOCASE, a.albumId, s.discNumber, s.trackNumber, s.title
        """,
    )
    abstract suspend fun getSongRowsOfGenre(genre: String): List<SongRow>

    /** Jahrzehnte (`startYear` = 1970, 1980, …) mit der Zahl ihrer sichtbaren Alben; Alben ohne plausibles Jahr fehlen. */
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
     * Je Album seine Genres (Schreibweisen wie in den Songs, leere ausgelassen), für die Genre-Auswahl der Bibliothek; Alben
     * ohne Genre fehlen.
     */
    @Query(
        """
        SELECT DISTINCT s.albumId, s.genre FROM songs s
        WHERE s.genre IS NOT NULL AND TRIM(s.genre) != '' AND $SONG_VISIBLE
        """,
    )
    abstract fun observeAlbumGenreRows(): Flow<List<AlbumGenreRow>>

    /**
     * Alben, auf denen [artistId] mindestens einen Song singt bzw. spielt, ohne Album-Künstler zu sein (Gäste, Sampler),
     * nach Jahr.
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
     * Alle Songs eines Künstlers: seine Alben ganz und von anderen Alben die Songs, die er singt bzw. spielt; Album für Album
     * nach Jahr, darin in Albumreihenfolge.
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

    /** Die meistgehörten Songs eines Künstlers (wie [getSongRowsOfArtist]); nie gehörte fehlen. */
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

    /** Mix-Merkmale der Songs [songIds] (höchstens ~900, SQLite-Variablenlimit). */
    @Query("$SELECT_MIX_CANDIDATES WHERE s.songId IN (:songIds)")
    abstract suspend fun getMixRows(songIds: List<Long>): List<MixCandidateRow>

    /**
     * Bis zu [limit] zufällige Songs, die zum Ausgangspunkt eines Mixes passen könnten: gleiches Genre (ohne Unterschied von
     * Groß/Klein), einer der Künstler [artistIds] (als Song- oder Album-Künstler) oder ein Jahr zwischen [fromYear] und
     * [toYear]. Ohne Jahr `fromYear > toYear` übergeben.
     */
    @Query(
        """
        $SELECT_MIX_CANDIDATES
        WHERE s.genre COLLATE NOCASE IN (:genres)
           OR EXISTS (SELECT 1 FROM song_artists sa WHERE sa.songId = s.songId AND sa.artistId IN (:artistIds))
           OR EXISTS (SELECT 1 FROM album_artists aa WHERE aa.albumId = s.albumId AND aa.artistId IN (:artistIds))
           OR COALESCE(s.year, a.year) BETWEEN :fromYear AND :toYear
        ORDER BY RANDOM()
        LIMIT :limit
        """,
    )
    abstract suspend fun getMixCandidates(
        genres: List<String>,
        artistIds: List<Long>,
        fromYear: Int,
        toYear: Int,
        limit: Int,
    ): List<MixCandidateRow>

    /** Bis zu [limit] zufällige sichtbare Songs, um einen Mix aufzufüllen. */
    @Query("$SELECT_MIX_CANDIDATES ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomMixCandidates(limit: Int): List<MixCandidateRow>

    // --- Statistik ---

    /** Alle gezählten Wiedergaben sichtbarer Songs (Musik-Statistik). */
    @Query("SELECT pe.songId, s.albumId, pe.playedAt, pe.listenedMs FROM play_events pe JOIN songs s ON s.songId = pe.songId WHERE $SONG_VISIBLE")
    abstract fun observeMusicPlays(): Flow<List<MusicPlayRow>>

    /** Die zwischen [from] (einschließlich) und [to] (ausschließlich) meistgespielten Songs; bei Gleichstand der zuletzt gehörte. */
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

    /** Wie [observeTopSongs], je Album. */
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

    /** Wie [observeTopSongs], je Künstler: dem ersten des Songs, sonst dem ersten des Albums (wie beim Mix). */
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

    /** Wie [observeTopSongs], je Genre (Schreibweisen wie im Reiter „Genres“ zusammengefasst). */
    @Query(
        """
        SELECT NULL AS id, MIN(s.genre) AS title, NULL AS subtitle, NULL AS albumId, COUNT(*) AS plays, SUM(pe.listenedMs) AS listenedMs
        $FROM_PLAYS_IN_RANGE
        WHERE s.genre IS NOT NULL AND TRIM(s.genre) != ''
        GROUP BY s.genre COLLATE NOCASE
        ORDER BY plays DESC, MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeTopGenres(from: Long, to: Long, limit: Int): Flow<List<TopPlayedRow>>

    /** Die Songs, die die Quelle [sourceId] unter [remoteIds] führt (höchstens ~900). */
    @Query("SELECT remoteId, songId FROM song_sources WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    abstract suspend fun getSongIdsByRemoteId(sourceId: String, remoteIds: List<String>): List<RemoteSongIdRow>

    /** Welche Künstler zu welchem Album gehören (Albumkünstler); die Uhr leitet daraus ihre Künstler ab. */
    @Query("SELECT * FROM album_artists")
    abstract fun observeAlbumArtists(): Flow<List<AlbumArtistCrossRef>>

    @Query("$SELECT_SONG_ROWS ORDER BY s.title COLLATE NOCASE")
    abstract fun observeSongRows(): Flow<List<SongRow>>

    @Query("$SELECT_SONG_ROWS WHERE s.albumId = :albumId ORDER BY s.discNumber, s.trackNumber, s.title")
    abstract fun observeSongRowsOfAlbum(albumId: Long): Flow<List<SongRow>>

    @Query("$SELECT_SONG_ROWS WHERE s.isFavorite ORDER BY s.favoriteChangedAt DESC")
    abstract fun observeFavoriteSongRows(): Flow<List<SongRow>>

    @Query("SELECT isFavorite FROM songs WHERE songId = :songId")
    abstract fun observeIsFavorite(songId: Long): Flow<Boolean?>

    /** Reihenfolge entspricht nicht [songIds]; max. ~900 IDs pro Aufruf (SQLite-Variablenlimit). */
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
     * Die seit [since] am häufigsten gehörten Alben (mindestens [minPlays] gezählte Wiedergaben), bei Gleichstand das zuletzt
     * gehörte zuerst. `songCount` zählt alle Songs des Albums, nicht die Wiedergaben.
     */
    @Query(
        """
        SELECT a.albumId, a.title, a.albumArtistDisplay, a.year,
               (SELECT COUNT(*) FROM songs cs WHERE cs.albumId = a.albumId) AS songCount
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

    /** Alben, die schon einmal gespielt wurden, zuletzt aber vor [playedBefore]; das älteste zuerst. */
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

    /** Höchste vergebene `songId` (0 ohne Songs); Ids werden nie wiederverwendet, spätere Songs haben also eine größere. */
    @Query("SELECT COALESCE(MAX(songId), 0) FROM songs")
    abstract suspend fun maxSongId(): Long

    /** Sichtbare Songs, die nach [songId] angelegt wurden. */
    @Query("SELECT COUNT(*) FROM songs s WHERE s.songId > :songId AND $SONG_VISIBLE")
    abstract suspend fun countVisibleSongsAfter(songId: Long): Int

    /** Alben mit sichtbaren Songs, die nach [songId] angelegt wurden; `songCount` zählt nur diese, die meisten zuerst. */
    @Query("$SELECT_ALBUM_ROWS AND s.songId > :songId GROUP BY a.albumId ORDER BY songCount DESC, a.sortTitle")
    abstract suspend fun albumsWithSongsAfter(songId: Long): List<AlbumRow>

    @Query("$SELECT_ALBUM_ROWS GROUP BY a.albumId ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomAlbumRows(limit: Int): List<AlbumRow>

    @Query("$SELECT_ARTIST_ROWS GROUP BY ar.artistId ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomArtistRows(limit: Int): List<ArtistRow>

    // --- Volltextsuche (FTS4-MATCH-Ausdruck, z. B. "sig* ros*") ---

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

    // --- Wiedergabe & Artwork ---

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

    /** Die eingeschalteten Quellen eines Songs, lokale zuerst, dann nach Priorität (Titel-Info). */
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

    /** Bevorzugt die Cover-Quelle des Albums, sonst irgendeine Quelle eines Album-Songs. */
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

    /** Ändert sich, sobald Alben oder Interpreten hinzukommen oder wegfallen (Anlass, ihre Bilder für offline zu laden). */
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

    // --- Aufräumen ---

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
     * Räumt nach dem Entfernen von Quellen bzw. nach einem Mark-and-Sweep-Abgleich auf:
     * Songs ohne Quelle, danach leere Alben, nicht mehr referenzierte Künstler und
     * Cover-Verweise auf gelöschte Quellen.
     */
    @Transaction
    open suspend fun cleanupOrphans() {
        deleteSongsWithoutSources()
        deleteAlbumsWithoutSongs()
        deleteUnreferencedArtists()
        repairAlbumCovers()
    }
}
