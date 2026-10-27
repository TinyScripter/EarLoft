package keinabersmart.kleinaberlaut.core.data.sync

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import keinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.datasource.RawArtist
import keinabersmart.kleinaberlaut.core.datasource.RawTrack
import keinabersmart.kleinaberlaut.core.datasource.SourceBatch
import keinabersmart.kleinaberlaut.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var syncer: LibrarySyncer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KleinaberlautDatabase::class.java,
        ).allowMainThreadQueries().build()
        syncer = LibrarySyncer(
            database = db,
            sourceDao = db.sourceDao(),
            mergeDao = db.mergeDao(),
            libraryDao = db.libraryDao(),
            mergeResolver = MergeResolver(db.mergeDao()),
            registry = NoSources,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `same song from two sources appears once with two sources`() = runTest {
        syncer.sync(FakeSource("local", listOf(track("1", "Hoppípolla", track = 3))))
        syncer.sync(FakeSource("navidrome", listOf(track("x", "Hoppipolla", track = 3))))

        val songs = db.libraryDao().observeSongRows().first()
        assertEquals(1, songs.size)
        assertEquals(2, db.libraryDao().getSourcesOfSong(songs.single().songId).size)
    }

    @Test
    fun `albums with the same name but different years stay separate`() = runTest {
        syncer.sync(
            FakeSource(
                "local",
                listOf(
                    track("1", "Buddy Holly", album = "Weezer", artist = "Weezer", year = 1994),
                    track("2", "Island in the Sun", album = "Weezer", artist = "Weezer", year = 2001),
                ),
            ),
        )

        assertEquals(2, db.libraryDao().observeAlbumRows().first().size)
    }

    @Test
    fun `missing year merges with the only candidate`() = runTest {
        syncer.sync(FakeSource("local", listOf(track("1", "Glósóli", track = 2, year = 2005))))
        syncer.sync(FakeSource("navidrome", listOf(track("x", "Glósóli", track = 2, year = null))))

        val albums = db.libraryDao().observeAlbumRows().first()
        assertEquals(1, albums.size)
        assertEquals(2005, albums.single().year)
    }

    @Test
    fun `different musicbrainz ids are never merged`() = runTest {
        syncer.sync(FakeSource("local", listOf(track("1", "Glósóli", mbidAlbum = "release-a"))))
        syncer.sync(FakeSource("navidrome", listOf(track("x", "Glósóli", mbidAlbum = "release-b"))))

        assertEquals(2, db.libraryDao().observeAlbumRows().first().size)
    }

    @Test
    fun `tracks removed from a source disappear after the next full sync`() = runTest {
        syncer.sync(FakeSource("local", listOf(track("1", "Glósóli", track = 2), track("2", "Hoppípolla", track = 3))))
        syncer.sync(FakeSource("local", listOf(track("2", "Hoppípolla", track = 3))))

        assertEquals(listOf("Hoppípolla"), db.libraryDao().observeSongRows().first().map { it.title })
    }

    @Test
    fun `failed scan deletes nothing`() = runTest {
        syncer.sync(FakeSource("local", listOf(track("1", "Glósóli"))))

        runCatching { syncer.sync(FakeSource("local", emptyList(), failAfterFirstBatch = true)) }

        assertEquals(1, db.libraryDao().observeSongRows().first().size)
    }

    @Test
    fun `retagged file keeps its song id`() = runTest {
        syncer.sync(FakeSource("local", listOf(track("1", "Glosoli", lastModified = 1))))
        val before = db.libraryDao().observeSongRows().first().single()

        syncer.sync(FakeSource("local", listOf(track("1", "Glósóli", lastModified = 2))))

        val after = db.libraryDao().observeSongRows().first().single()
        assertEquals(before.songId, after.songId)
        assertEquals("Glósóli", after.title)
    }

    private fun track(
        remoteId: String,
        title: String,
        track: Int = 1,
        album: String = "Takk...",
        artist: String = "Sigur Rós",
        year: Int? = 2005,
        mbidAlbum: String? = null,
        lastModified: Long = 1,
    ) = RawTrack(
        remoteId = remoteId,
        title = title,
        artistDisplay = artist,
        artists = listOf(RawArtist(artist)),
        albumTitle = album,
        albumArtistDisplay = artist,
        albumArtists = listOf(RawArtist(artist)),
        trackNumber = track,
        durationMs = 300_000,
        year = year,
        mbidAlbum = mbidAlbum,
        lastModified = lastModified,
    )

    private class FakeSource(
        override val sourceId: String,
        private val tracks: List<RawTrack>,
        private val failAfterFirstBatch: Boolean = false,
    ) : MusicDataSource {
        override val type = if (sourceId == "local") SourceType.LOCAL else SourceType.SUBSONIC
        override val displayName = sourceId

        override fun fullScan(): Flow<SourceBatch> = flow {
            emit(SourceBatch(tracks))
            if (failAfterFirstBatch) error("Verbindung abgebrochen")
        }

        override suspend fun resolvePlayback(remoteId: String) = PlaybackLocation.LocalFile(Uri.EMPTY)

        override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
    }

    private object NoSources : MusicDataSources {
        override suspend fun all(): List<MusicDataSource> = emptyList()
        override suspend fun get(sourceId: String): MusicDataSource? = null
    }
}
