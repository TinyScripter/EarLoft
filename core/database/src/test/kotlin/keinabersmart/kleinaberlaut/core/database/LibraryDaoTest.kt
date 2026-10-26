package keinabersmart.kleinaberlaut.core.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.entity.AlbumArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.AlbumEntity
import keinabersmart.kleinaberlaut.core.database.entity.ArtistEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.SongEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.model.SourceType
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

    private lateinit var db: KleinaberlautDatabase
    private lateinit var sourceDao: SourceDao
    private lateinit var libraryDao: LibraryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KleinaberlautDatabase::class.java,
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
    }
}
