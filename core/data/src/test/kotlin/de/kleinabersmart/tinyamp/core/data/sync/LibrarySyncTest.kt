package de.kleinabersmart.tinyamp.core.data.sync

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.entity.DownloadEntity
import de.kleinabersmart.tinyamp.core.datasource.MusicDataSource
import de.kleinabersmart.tinyamp.core.datasource.PlaybackLocation
import de.kleinabersmart.tinyamp.core.datasource.RawArtist
import de.kleinabersmart.tinyamp.core.datasource.RawTrack
import de.kleinabersmart.tinyamp.core.datasource.SourceBatch
import de.kleinabersmart.tinyamp.core.model.DownloadState
import de.kleinabersmart.tinyamp.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {

    private lateinit var db: TinyAmpDatabase
    private lateinit var syncer: LibrarySyncer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TinyAmpDatabase::class.java,
        ).allowMainThreadQueries().build()
        syncer = db.librarySyncer()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `same song from two sources appears once with two sources`() = runTest {
        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Hoppípolla", track = 3))))
        syncer.syncAdding(db, FakeSource("navidrome", listOf(track("x", "Hoppipolla", track = 3))))

        val songs = db.libraryDao().observeSongRows().first()
        assertEquals(1, songs.size)
        assertEquals(2, db.libraryDao().getSourcesOfSong(songs.single().songId).size)
    }

    @Test
    fun `a server removed while its sync waited does not come back`() = runTest {
        val server = FakeSource("navidrome", listOf(track("x", "Hoppipolla", track = 3)))
        syncer.syncAdding(db, server)
        db.sourceDao().delete("navidrome")

        // Der Abgleich hielt das alte Objekt noch (wartete am Mutex), als der Server entfernt wurde.
        syncer.sync(server)

        assertEquals(null, db.sourceDao().get("navidrome"))
        assertTrue(db.libraryDao().observeSongRows().first().isEmpty())
    }

    @Test
    fun `albums with the same name but different years stay separate`() = runTest {
        syncer.syncAdding(db,
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
        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Glósóli", track = 2, year = 2005))))
        syncer.syncAdding(db, FakeSource("navidrome", listOf(track("x", "Glósóli", track = 2, year = null))))

        val albums = db.libraryDao().observeAlbumRows().first()
        assertEquals(1, albums.size)
        assertEquals(2005, albums.single().year)
    }

    @Test
    fun `different musicbrainz ids are never merged`() = runTest {
        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Glósóli", mbidAlbum = "release-a"))))
        syncer.syncAdding(db, FakeSource("navidrome", listOf(track("x", "Glósóli", mbidAlbum = "release-b"))))

        assertEquals(2, db.libraryDao().observeAlbumRows().first().size)
    }

    @Test
    fun `second source fills in a missing song musicbrainz id`() = runTest {
        syncer.syncAdding(db, FakeSource("navidrome", listOf(track("x", "Glósóli", track = 2))))
        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Glósóli", track = 2, mbidTrack = "recording-a"))))

        assertEquals("recording-a", db.mergeDao().getSong(db.libraryDao().observeSongRows().first().single().songId)!!.mbid)
    }

    @Test
    fun `tracks removed from a source disappear after the next full sync`() = runTest {
        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Glósóli", track = 2), track("2", "Hoppípolla", track = 3))))
        syncer.syncAdding(db, FakeSource("local", listOf(track("2", "Hoppípolla", track = 3))))

        assertEquals(listOf("Hoppípolla"), db.libraryDao().observeSongRows().first().map { it.title })
    }

    @Test
    fun `failed scan deletes nothing`() = runTest {
        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Glósóli"))))

        runCatching { syncer.syncAdding(db, FakeSource("local", emptyList(), failAfterFirstBatch = true)) }

        assertEquals(1, db.libraryDao().observeSongRows().first().size)
    }

    @Test
    fun `retagged file keeps its song id`() = runTest {
        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Glosoli", lastModified = 1))))
        val before = db.libraryDao().observeSongRows().first().single()

        syncer.syncAdding(db, FakeSource("local", listOf(track("1", "Glósóli", lastModified = 2))))

        val after = db.libraryDao().observeSongRows().first().single()
        assertEquals(before.songId, after.songId)
        assertEquals("Glósóli", after.title)
    }

    @Test
    fun `download is fetched again only when the server file itself changed`() = runTest {
        syncer.syncAdding(db, FakeSource("nd", listOf(track("x", "Gong", lastModified = 1, sizeBytes = 100))))
        val songId = db.libraryDao().observeSongRows().first().single().songId
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:x", songId, DownloadState.DONE, filePath = "/dl/x.flac")))

        // Nur Metadaten (z. B. Album-Cover) geändert: Download bleibt.
        syncer.syncAdding(db, FakeSource("nd", listOf(track("x", "Gong", lastModified = 2, sizeBytes = 100))))
        assertEquals(DownloadState.DONE, db.downloadDao().get("nd:x")!!.state)

        syncer.syncAdding(db, FakeSource("nd", listOf(track("x", "Gong", lastModified = 3, sizeBytes = 120))))
        assertEquals(DownloadState.QUEUED, db.downloadDao().get("nd:x")!!.state)
    }

    private fun track(
        remoteId: String,
        title: String,
        track: Int = 1,
        album: String = "Takk...",
        artist: String = "Sigur Rós",
        year: Int? = 2005,
        mbidAlbum: String? = null,
        mbidTrack: String? = null,
        lastModified: Long = 1,
        sizeBytes: Long? = null,
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
        mbidTrack = mbidTrack,
        lastModified = lastModified,
        sizeBytes = sizeBytes,
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
}
