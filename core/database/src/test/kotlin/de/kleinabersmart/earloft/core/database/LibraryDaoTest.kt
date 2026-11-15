package de.kleinabersmart.earloft.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.NOT_SERVER_TYPES
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryDaoTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var sourceDao: SourceDao
    private lateinit var libraryDao: LibraryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EarLoftDatabase::class.java,
        ).allowMainThreadQueries().build()
        sourceDao = db.sourceDao()
        libraryDao = db.libraryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `removing a source keeps songs that another source still provides`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome"))
        val albumId = libraryDao.insertAlbum(album("Ágætis byrjun", "Sigur Rós"))
        val shared = libraryDao.insertSong(song(albumId, "Svefn-g-englar", track = 1))
        val localOnly = libraryDao.insertSong(song(albumId, "Starálfur", track = 2))
        libraryDao.insertSongSource(songSource(shared, LOCAL, "1"))
        libraryDao.insertSongSource(songSource(shared, NAVIDROME, "a"))
        libraryDao.insertSongSource(songSource(localOnly, LOCAL, "2"))

        sourceDao.delete(LOCAL)
        libraryDao.cleanupOrphans()

        val songs = libraryDao.observeSongsOfAlbum(albumId).first()
        assertEquals(listOf("Svefn-g-englar"), songs.map { it.title })
        assertEquals(listOf("$NAVIDROME:a"), libraryDao.getSourcesOfSong(shared).map { it.uid })
    }

    @Test
    fun `server queries skip the device sources and follow isServer`() = runTest {
        assertEquals(
            SourceType.entries.filterNot { it.isServer }.joinToString(", ", "(", ")") { "'${it.name}'" },
            NOT_SERVER_TYPES,
        )
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        sourceDao.upsert(SourceEntity(sourceId = PHONE, type = SourceType.PHONE, name = "Handy"))
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome"))
        val albumId = libraryDao.insertAlbum(album("Ágætis byrjun", "Sigur Rós"))
        val songId = libraryDao.insertSong(song(albumId, "Svefn-g-englar", track = 1))
        libraryDao.insertSongSource(songSource(songId, LOCAL, "1"))
        libraryDao.insertSongSource(songSource(songId, PHONE, "local:1"))
        libraryDao.insertSongSource(songSource(songId, NAVIDROME, "a"))

        assertEquals(listOf(NAVIDROME), sourceDao.getServers().map { it.sourceId })
        assertEquals(listOf("$NAVIDROME:a"), db.syncDao().getServerSources(songId).map { it.uid })
        assertEquals(listOf("$NAVIDROME:a"), db.lyricsDao().getServerSources(songId).map { it.uid })

        // Ein ausgeschalteter Server wird nicht nach Songtexten gefragt.
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome", enabled = false))
        assertTrue(db.lyricsDao().getServerSources(songId).isEmpty())
    }

    @Test
    fun `cleanup removes empty albums and unreferenced artists`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        val artistId = libraryDao.insertArtist(
            ArtistEntity(name = "Sigur Rós", sortName = "Sigur Rós", normName = "sigurros"),
        )
        val albumId = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        val songId = libraryDao.insertSong(song(albumId, "Hoppípolla", track = 3))
        libraryDao.insertSongSource(songSource(songId, LOCAL, "7"))
        libraryDao.insertSongArtist(SongArtistCrossRef(songId, artistId, position = 0))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(albumId, artistId, position = 0))

        sourceDao.delete(LOCAL)
        libraryDao.cleanupOrphans()

        assertTrue(libraryDao.observeAlbums().first().isEmpty())
        assertTrue(libraryDao.observeArtists().first().isEmpty())
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `the same remote id cannot be stored twice for one source`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome"))
        val albumId = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        val songId = libraryDao.insertSong(song(albumId, "Glósóli", track = 2))
        libraryDao.insertSongSource(songSource(songId, NAVIDROME, "x"))
        libraryDao.insertSongSource(songSource(songId, NAVIDROME, "x").copy(uid = "other"))
    }

    @Test
    fun `full text search ignores case and diacritics`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        val artistId = libraryDao.insertArtist(
            ArtistEntity(name = "Sigur Rós", sortName = "Sigur Rós", normName = "sigurros"),
        )
        val albumId = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(albumId, artistId, position = 0))
        val songId = libraryDao.insertSong(song(albumId, "Hoppípolla", track = 3))
        libraryDao.insertSongSource(songSource(songId, LOCAL, "7"))

        assertEquals(listOf("Hoppípolla"), libraryDao.searchSongRows("HOPPI*", 10).map { it.title })
        assertEquals(listOf("Takk..."), libraryDao.searchAlbumRows("takk*", 10).map { it.title })
        assertEquals(listOf("Sigur Rós"), libraryDao.searchArtistRows("sigur* ros*", 10).map { it.name })
        assertTrue(libraryDao.searchSongRows("glosoli*", 10).isEmpty())
    }

    @Test
    fun `album rows count songs and queue skips unknown songs`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        val albumId = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        val first = libraryDao.insertSong(song(albumId, "Glósóli", track = 2))
        val second = libraryDao.insertSong(song(albumId, "Hoppípolla", track = 3))
        libraryDao.insertSongSource(songSource(first, LOCAL, "1"))
        libraryDao.insertSongSource(songSource(second, LOCAL, "2"))

        assertEquals(2, libraryDao.observeAlbumRows().first().single().songCount)

        val playbackDao = db.playbackDao()
        playbackDao.replaceQueue(listOf(second, 999L, first))
        assertEquals(listOf(second, first), playbackDao.getQueueSongIds())
    }

    @Test
    fun `genres merge spellings, count visible songs and list their albums`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome", enabled = false))
        val takk = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        val agaetis = libraryDao.insertAlbum(album("Ágætis byrjun", "Sigur Rós"))
        val hidden = libraryDao.insertAlbum(album("Kveikur", "Sigur Rós"))
        val songs = listOf(
            libraryDao.insertSong(song(takk, "Glósóli", track = 2).copy(genre = "Post-Rock")),
            libraryDao.insertSong(song(takk, "Hoppípolla", track = 3).copy(genre = "post-rock")),
            libraryDao.insertSong(song(agaetis, "Svefn-g-englar", track = 1).copy(genre = "Post-Rock")),
            libraryDao.insertSong(song(agaetis, "Starálfur", track = 2).copy(genre = "Ambient")),
            libraryDao.insertSong(song(agaetis, "Flugufrelsarinn", track = 3).copy(genre = " ")),
        )
        songs.forEachIndexed { index, songId -> libraryDao.insertSongSource(songSource(songId, LOCAL, "$index")) }
        // Nur auf einem ausgeschalteten Server: zählt nicht.
        val hiddenSong = libraryDao.insertSong(song(hidden, "Brennisteinn", track = 1).copy(genre = "Post-Rock"))
        libraryDao.insertSongSource(songSource(hiddenSong, NAVIDROME, "k"))

        val genres = libraryDao.observeGenreRows().first()
        assertEquals(listOf("Ambient", "Post-Rock"), genres.map { it.name })
        assertEquals(2, genres[1].albumCount)
        assertEquals(3, genres[1].songCount)

        assertEquals(
            setOf(takk to "Post-Rock", takk to "post-rock", agaetis to "Post-Rock", agaetis to "Ambient"),
            libraryDao.observeAlbumGenreRows().first().map { it.albumId to it.genre }.toSet(),
        )

        assertEquals(
            listOf("Glósóli", "Hoppípolla", "Svefn-g-englar", "Starálfur", "Flugufrelsarinn"),
            libraryDao.getSongRowsOfAlbums(listOf(agaetis, takk)).sortedBy { it.albumId != takk }.map { it.title },
        )

        val albums = libraryDao.observeAlbumRowsOfGenre("POST-ROCK").first()
        assertEquals(listOf("Takk...", "Ágætis byrjun").sorted(), albums.map { it.title }.sorted())
        // Die Zahl der Songs gilt dem ganzen Album, nicht nur dem Genre.
        assertEquals(3, albums.single { it.albumId == agaetis }.songCount)
        assertEquals(
            listOf("Glósóli", "Hoppípolla", "Svefn-g-englar").sorted(),
            libraryDao.getSongRowsOfGenre("post-rock").map { it.title }.sorted(),
        )
    }

    @Test
    fun `decades group visible albums by year`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome", enabled = false))
        val takk = libraryDao.insertAlbum(album("Takk...", "Sigur Rós").copy(year = 2005))
        val agaetis = libraryDao.insertAlbum(album("Ágætis byrjun", "Sigur Rós").copy(year = 1999))
        val von = libraryDao.insertAlbum(album("Von", "Sigur Rós").copy(year = 1997))
        val noYear = libraryDao.insertAlbum(album("Demo", "Sigur Rós").copy(year = 0))
        val hidden = libraryDao.insertAlbum(album("Kveikur", "Sigur Rós").copy(year = 2013))
        listOf(takk, agaetis, von, noYear).forEachIndexed { index, albumId ->
            val songId = libraryDao.insertSong(song(albumId, "Song $index", track = 1))
            libraryDao.insertSongSource(songSource(songId, LOCAL, "$index"))
        }
        // Nur auf einem ausgeschalteten Server: zählt nicht.
        val hiddenSong = libraryDao.insertSong(song(hidden, "Brennisteinn", track = 1))
        libraryDao.insertSongSource(songSource(hiddenSong, NAVIDROME, "k"))

        val decades = libraryDao.observeDecadeRows().first()
        assertEquals(listOf(1990 to 2, 2000 to 1), decades.map { it.startYear to it.albumCount })
    }

    @Test
    fun `albums, artists and songs follow the chosen order`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        val beatles = libraryDao.insertArtist(ArtistEntity(name = "The Beatles", sortName = "Beatles", normName = "thebeatles"))
        val abba = libraryDao.insertArtist(ArtistEntity(name = "ABBA", sortName = "ABBA", normName = "abba"))
        val help = libraryDao.insertAlbum(album("Help!", "The Beatles").copy(year = 1965))
        val arrival = libraryDao.insertAlbum(album("Arrival", "ABBA").copy(year = 1976))
        val abbeyRoad = libraryDao.insertAlbum(album("Abbey Road", "The Beatles").copy(year = 1969))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(help, beatles, 0))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(abbeyRoad, beatles, 0))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(arrival, abba, 0))
        val songs = listOf(
            Triple(help, "Yesterday", beatles) to 3L,
            Triple(arrival, "Dancing Queen", abba) to 1L,
            Triple(abbeyRoad, "Something", beatles) to 2L,
        ).mapIndexed { index, (song, added) ->
            val (albumId, title, artistId) = song
            val songId = libraryDao.insertSong(song(albumId, title, track = 1).copy(dateAdded = added))
            libraryDao.insertSongSource(songSource(songId, LOCAL, "$index"))
            libraryDao.insertSongArtist(SongArtistCrossRef(songId, artistId, 0))
            songId
        }

        suspend fun albums(sort: String) = libraryDao.observeAlbumRowsSorted(sort).first().map { it.title }
        assertEquals(listOf("Abbey Road", "Arrival", "Help!"), albums("TITLE"))
        // „The Beatles“ steht unter B, darin nach Jahr.
        assertEquals(listOf("Arrival", "Help!", "Abbey Road"), albums("ARTIST"))
        assertEquals(listOf("Arrival", "Abbey Road", "Help!"), albums("YEAR"))
        assertEquals(listOf("Help!", "Abbey Road", "Arrival"), albums("RECENTLY_ADDED"))

        db.playbackDao().insertPlayEventIfSongExists(PlayEventEntity(songId = songs[1], playedAt = 100, listenedMs = 1))
        db.playbackDao().insertPlayEventIfSongExists(PlayEventEntity(songId = songs[0], playedAt = 200, listenedMs = 1))
        assertEquals(listOf("Help!", "Arrival", "Abbey Road"), libraryDao.observeAlbumRowsByLastPlayed().first().map { it.title })

        assertEquals(listOf("ABBA", "The Beatles"), libraryDao.observeArtistRowsSorted("NAME").first().map { it.name })
        assertEquals(listOf("The Beatles", "ABBA"), libraryDao.observeArtistRowsSorted("ALBUM_COUNT").first().map { it.name })

        suspend fun songs(sort: String) = libraryDao.observeSongRowsSorted(sort).first().map { it.title }
        assertEquals(listOf("Dancing Queen", "Something", "Yesterday"), songs("TITLE"))
        assertEquals(listOf("Dancing Queen", "Something", "Yesterday"), songs("ARTIST"))
        assertEquals(listOf("Something", "Dancing Queen", "Yesterday"), songs("ALBUM"))
        assertEquals(listOf("Yesterday", "Something", "Dancing Queen"), songs("RECENTLY_ADDED"))
    }

    @Test
    fun `most played albums count plays in the period`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        val takk = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        val agaetis = libraryDao.insertAlbum(album("Ágætis byrjun", "Sigur Rós"))
        val glosoli = libraryDao.insertSong(song(takk, "Glósóli", track = 2))
        val hoppipolla = libraryDao.insertSong(song(takk, "Hoppípolla", track = 3))
        val svefn = libraryDao.insertSong(song(agaetis, "Svefn-g-englar", track = 1))
        listOf(glosoli, hoppipolla, svefn).forEachIndexed { index, songId -> libraryDao.insertSongSource(songSource(songId, LOCAL, "$index")) }
        val playback = db.playbackDao()
        // Takk: drei Wiedergaben im Zeitraum, eine davor; Ágætis: vier, aber nur zwei im Zeitraum.
        listOf(glosoli to 1_000L, hoppipolla to 1_100L, glosoli to 1_200L, glosoli to 10L, svefn to 1_300L, svefn to 1_400L, svefn to 5L, svefn to 6L)
            .forEach { (songId, at) -> playback.insertPlayEventIfSongExists(PlayEventEntity(songId = songId, playedAt = at, listenedMs = 1)) }

        val rows = libraryDao.observeMostPlayedAlbumRows(since = 1_000, minPlays = 2, limit = 10).first()
        assertEquals(listOf("Takk...", "Ágætis byrjun"), rows.map { it.title })
        assertEquals(2, rows.first().songCount)
        assertEquals(listOf("Takk..."), libraryDao.observeMostPlayedAlbumRows(since = 1_000, minPlays = 3, limit = 10).first().map { it.title })
    }

    @Test
    fun `most played albums do not count songs of a switched off server`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome", enabled = false))
        val takk = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        val glosoli = libraryDao.insertSong(song(takk, "Glósóli", track = 2))
        val hidden = libraryDao.insertSong(song(takk, "Hoppípolla", track = 3))
        libraryDao.insertSongSource(songSource(glosoli, LOCAL, "1"))
        libraryDao.insertSongSource(songSource(hidden, NAVIDROME, "a"))
        val playback = db.playbackDao()
        playback.insertPlayEventIfSongExists(PlayEventEntity(songId = glosoli, playedAt = 1_000, listenedMs = 1))

        val rows = libraryDao.observeMostPlayedAlbumRows(since = 0, minPlays = 1, limit = 10).first()

        assertEquals(1, rows.single().songCount)
        assertEquals(1, libraryDao.observeAlbumRows().first().single().songCount)
    }

    @Test
    fun `artist page lists all songs, most played and appearances`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        val jonsi = libraryDao.insertArtist(ArtistEntity(name = "Jónsi", sortName = "Jónsi", normName = "jonsi"))
        val sigur = libraryDao.insertArtist(ArtistEntity(name = "Sigur Rós", sortName = "Sigur Rós", normName = "sigurros"))
        val go = libraryDao.insertAlbum(album("Go", "Jónsi").copy(year = 2010))
        val takk = libraryDao.insertAlbum(album("Takk...", "Sigur Rós").copy(year = 2005))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(go, jonsi, 0))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(takk, sigur, 0))
        val boyLilikoi = libraryDao.insertSong(song(go, "Boy Lilikoi", track = 2))
        val goDo = libraryDao.insertSong(song(go, "Go Do", track = 1))
        val glosoli = libraryDao.insertSong(song(takk, "Glósóli", track = 2))
        val hoppipolla = libraryDao.insertSong(song(takk, "Hoppípolla", track = 3))
        listOf(boyLilikoi, goDo, glosoli, hoppipolla).forEachIndexed { index, songId ->
            libraryDao.insertSongSource(songSource(songId, LOCAL, "$index"))
        }
        libraryDao.insertSongArtist(SongArtistCrossRef(goDo, jonsi, 0))
        libraryDao.insertSongArtist(SongArtistCrossRef(boyLilikoi, jonsi, 0))
        libraryDao.insertSongArtist(SongArtistCrossRef(glosoli, sigur, 0))
        // Jónsi singt auch auf „Takk...“ mit (nur dieser eine Song).
        libraryDao.insertSongArtist(SongArtistCrossRef(hoppipolla, sigur, 0))
        libraryDao.insertSongArtist(SongArtistCrossRef(hoppipolla, jonsi, 1))

        // Nach Jahr: erst „Takk...“ (2005) mit nur Hoppípolla, dann „Go“ in Albumreihenfolge.
        assertEquals(listOf("Hoppípolla", "Go Do", "Boy Lilikoi"), libraryDao.getSongRowsOfArtist(jonsi).map { it.title })
        assertEquals(listOf("Takk..."), libraryDao.observeAppearsOnAlbumRows(jonsi).first().map { it.title })
        assertTrue(libraryDao.observeAppearsOnAlbumRows(sigur).first().isEmpty())

        assertTrue(libraryDao.observeTopSongRowsOfArtist(jonsi, 5).first().isEmpty())
        val playback = db.playbackDao()
        listOf(boyLilikoi to 1L, hoppipolla to 2L, hoppipolla to 3L, glosoli to 4L)
            .forEach { (songId, at) -> playback.insertPlayEventIfSongExists(PlayEventEntity(songId = songId, playedAt = at, listenedMs = 1)) }
        assertEquals(listOf("Hoppípolla", "Boy Lilikoi"), libraryDao.observeTopSongRowsOfArtist(jonsi, 5).first().map { it.title })
        assertEquals(listOf("Hoppípolla"), libraryDao.observeTopSongRowsOfArtist(jonsi, 1).first().map { it.title })
    }

    @Test
    fun `mix candidates match genre, artist or year and carry the play history`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome", enabled = false))
        val jonsi = libraryDao.insertArtist(ArtistEntity(name = "Jónsi", sortName = "Jónsi", normName = "jonsi"))
        val sigur = libraryDao.insertArtist(ArtistEntity(name = "Sigur Rós", sortName = "Sigur Rós", normName = "sigurros"))
        val go = libraryDao.insertAlbum(album("Go", "Jónsi").copy(year = 2010))
        val takk = libraryDao.insertAlbum(album("Takk...", "Sigur Rós").copy(year = 2005))
        val old = libraryDao.insertAlbum(album("Old", "Someone").copy(year = 1970))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(go, jonsi, 0))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(takk, sigur, 0))
        val goDo = libraryDao.insertSong(song(go, "Go Do", track = 1).copy(genre = "Post-Rock"))
        val glosoli = libraryDao.insertSong(song(takk, "Glósóli", track = 2).copy(genre = "post-rock"))
        val hoppipolla = libraryDao.insertSong(song(takk, "Hoppípolla", track = 3).copy(genre = "Ambient"))
        val oldSong = libraryDao.insertSong(song(old, "Old Song", track = 1).copy(genre = "Jazz", year = 2008))
        val hidden = libraryDao.insertSong(song(old, "Hidden", track = 2).copy(genre = "Post-Rock"))
        listOf(goDo, glosoli, hoppipolla, oldSong).forEachIndexed { index, songId ->
            libraryDao.insertSongSource(songSource(songId, LOCAL, "$index"))
        }
        libraryDao.insertSongSource(songSource(hidden, NAVIDROME, "h"))
        libraryDao.insertSongArtist(SongArtistCrossRef(goDo, jonsi, 0))
        db.playbackDao().insertPlayEventIfSongExists(PlayEventEntity(songId = goDo, playedAt = 5, listenedMs = 1))
        db.playbackDao().insertPlayEventIfSongExists(PlayEventEntity(songId = goDo, playedAt = 9, listenedMs = 1))

        val seed = libraryDao.getMixRows(listOf(goDo, glosoli)).associateBy { it.songId }
        assertEquals(jonsi, seed.getValue(goDo).artistId)
        // Ohne Song-Künstler zählt der Album-Künstler, ohne Song-Jahr das des Albums.
        assertEquals(sigur, seed.getValue(glosoli).artistId)
        assertEquals(2005, seed.getValue(glosoli).year)
        assertEquals(2, seed.getValue(goDo).playCount)
        assertEquals(9L, seed.getValue(goDo).lastPlayedAt)

        suspend fun ids(genres: List<String>, artists: List<Long>, from: Int, to: Int) =
            libraryDao.getMixCandidates(genres, artists, from, to, limit = 10).map { it.songId }.toSet()
        // Genre ohne Unterschied von Groß/Klein; der Song des ausgeschalteten Servers fehlt.
        assertEquals(setOf(goDo, glosoli), ids(listOf("POST-ROCK"), emptyList(), 1, 0))
        assertEquals(setOf(glosoli, hoppipolla), ids(emptyList(), listOf(sigur), 1, 0))
        // Das Song-Jahr geht vor: „Old Song“ (2008) passt, obwohl sein Album von 1970 ist.
        assertEquals(setOf(goDo, oldSong), ids(emptyList(), emptyList(), 2007, 2012))
        assertEquals(4, libraryDao.getRandomMixCandidates(10).size)

        assertEquals(listOf(goDo), libraryDao.getSongIdsByRemoteId(LOCAL, listOf("0", "x")).map { it.songId })
    }

    @Test
    fun `music statistics rank songs, albums, artists and genres in the period`() = runTest {
        sourceDao.upsert(SourceEntity(sourceId = LOCAL, type = SourceType.LOCAL, name = "Gerät"))
        sourceDao.upsert(SourceEntity(sourceId = NAVIDROME, type = SourceType.SUBSONIC, name = "Navidrome", enabled = false))
        val jonsi = libraryDao.insertArtist(ArtistEntity(name = "Jónsi", sortName = "Jónsi", normName = "jonsi"))
        val sigur = libraryDao.insertArtist(ArtistEntity(name = "Sigur Rós", sortName = "Sigur Rós", normName = "sigurros"))
        val go = libraryDao.insertAlbum(album("Go", "Jónsi"))
        val takk = libraryDao.insertAlbum(album("Takk...", "Sigur Rós"))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(go, jonsi, 0))
        libraryDao.insertAlbumArtist(AlbumArtistCrossRef(takk, sigur, 0))
        val goDo = libraryDao.insertSong(song(go, "Go Do", track = 1).copy(genre = "Post-Rock"))
        val glosoli = libraryDao.insertSong(song(takk, "Glósóli", track = 2).copy(genre = "post-rock"))
        val hoppipolla = libraryDao.insertSong(song(takk, "Hoppípolla", track = 3))
        val hidden = libraryDao.insertSong(song(takk, "Hidden", track = 4).copy(genre = "Jazz"))
        listOf(goDo, glosoli, hoppipolla).forEachIndexed { index, songId -> libraryDao.insertSongSource(songSource(songId, LOCAL, "$index")) }
        libraryDao.insertSongSource(songSource(hidden, NAVIDROME, "h"))
        // Hoppípolla: Song-Künstler Jónsi statt Album-Künstler.
        libraryDao.insertSongArtist(SongArtistCrossRef(hoppipolla, jonsi, 0))
        val playback = db.playbackDao()
        // Im Zeitraum [100, 200): Glósóli 3×, Go Do 2×, Hoppípolla 1×; davor Hoppípolla 5×, Hidden ist ausgeblendet.
        (listOf(glosoli to 100L, glosoli to 110L, glosoli to 120L, goDo to 130L, goDo to 140L, hoppipolla to 150L, hidden to 160L, hidden to 170L) +
            List(5) { hoppipolla to it.toLong() })
            .forEach { (songId, at) -> playback.insertPlayEventIfSongExists(PlayEventEntity(songId = songId, playedAt = at, listenedMs = 10)) }

        val songs = libraryDao.observeTopSongs(100, 200, 10).first()
        assertEquals(listOf("Glósóli" to 3, "Go Do" to 2, "Hoppípolla" to 1), songs.map { it.title to it.plays })
        assertEquals(30L, songs.first().listenedMs)
        assertEquals(takk, songs.first().albumId)
        assertEquals(listOf("Takk..." to 4, "Go" to 2), libraryDao.observeTopAlbums(100, 200, 10).first().map { it.title to it.plays })
        // Gleichstand: zuletzt gehört zuerst (Hoppípolla zählt für Jónsi).
        assertEquals(listOf("Jónsi" to 3, "Sigur Rós" to 3), libraryDao.observeTopArtists(100, 200, 10).first().map { it.title to it.plays })
        // Schreibweisen zusammen; Songs ohne Genre fehlen.
        assertEquals(listOf(5), libraryDao.observeTopGenres(100, 200, 10).first().map { it.plays })
        assertEquals(listOf("Hoppípolla"), libraryDao.observeTopSongs(0, 100, 1).first().map { it.title })
        // Alle Wiedergaben sichtbarer Songs, ohne den ausgeschalteten Server.
        assertEquals(11, libraryDao.observeMusicPlays().first().size)
    }

    private fun album(title: String, artist: String) = AlbumEntity(
        title = title,
        sortTitle = title,
        normTitle = title.lowercase(),
        albumArtistDisplay = artist,
        normAlbumArtist = artist.lowercase(),
    )

    private fun song(albumId: Long, title: String, track: Int) = SongEntity(
        albumId = albumId,
        title = title,
        normTitle = title.lowercase(),
        artistDisplay = "Sigur Rós",
        trackNumber = track,
        durationMs = 300_000,
        dateAdded = 0,
    )

    private fun songSource(songId: Long, sourceId: String, remoteId: String) = SongSourceEntity(
        uid = "$sourceId:$remoteId",
        songId = songId,
        sourceId = sourceId,
        remoteId = remoteId,
        seenInGeneration = 1,
    )

    private companion object {
        const val LOCAL = "local"
        const val NAVIDROME = "navidrome"
        const val PHONE = "phone"
    }
}
