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
