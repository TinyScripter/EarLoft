package de.kleinabersmart.earloft.core.data.sync

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadExclusionEntity
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.Genre
import de.kleinabersmart.earloft.core.model.SourceType
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

    private lateinit var db: EarLoftDatabase
    private lateinit var syncer: LibrarySyncer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EarLoftDatabase::class.java,
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
        assertEquals(2, db.mergeDao().getSourcesOfSong(songs.single().songId).size)
    }

    @Test
    fun `a server removed while its sync waited does not come back`() = runTest {
        val server = FakeSource("navidrome", listOf(track("x", "Hoppipolla", track = 3)))
        syncer.syncAdding(db, server)
        db.sourceDao().delete("navidrome")

        // The sync still held the old object (waiting on the mutex) when the server was removed.
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

        // Only metadata (e.g. album cover) changed: the download stays.
        syncer.syncAdding(db, FakeSource("nd", listOf(track("x", "Gong", lastModified = 2, sizeBytes = 100))))
        assertEquals(DownloadState.DONE, db.downloadDao().get("nd:x")!!.state)

        syncer.syncAdding(db, FakeSource("nd", listOf(track("x", "Gong", lastModified = 3, sizeBytes = 120))))
        assertEquals(DownloadState.QUEUED, db.downloadDao().get("nd:x")!!.state)
    }

    @Test
    fun `two tracks of one source with the same title stay two songs`() = runTest {
        val aerosmith = track("8", "Walk This Way", track = 8, album = "Greatest Hits", artist = "Aerosmith")
        val runDmc = aerosmith.copy(remoteId = "22", trackNumber = 22, artistDisplay = "Run-DMC • Aerosmith", durationMs = 299_000)
        syncer.syncAdding(db, FakeSource("nd", listOf(aerosmith, runDmc)))
        assertEquals(2, db.libraryDao().observeSongRows().first().size)

        // A second source with only one of the versions is merged into one of the two.
        syncer.syncAdding(db, FakeSource("local", listOf(aerosmith.copy(remoteId = "1"))))
        assertEquals(2, db.libraryDao().observeSongRows().first().size)
    }

    @Test
    fun `two tracks of one source with the same recording id or the same position stay two songs`() = runTest {
        // The same recording on CD 1 and as a bonus; two "Interlude" without track number.
        val original = track("1", "Hey Jude", track = 3, mbidTrack = "rec-1")
        val bonus = original.copy(remoteId = "2", discNumber = 2, trackNumber = 9)
        val interlude = track("3", "Interlude", track = 0)
        val interlude2 = interlude.copy(remoteId = "4", durationMs = 60_000)
        syncer.syncAdding(db, FakeSource("nd", listOf(original, bonus, interlude, interlude2)))
        assertEquals(4, db.libraryDao().observeSongRows().first().size)

        // A second source with the same tracks merges each track into one of the songs, without new ones.
        syncer.syncAdding(db, FakeSource("local", listOf(original, bonus, interlude, interlude2).map { it.copy(remoteId = "l" + it.remoteId) }))
        assertEquals(4, db.libraryDao().observeSongRows().first().size)
    }

    @Test
    fun `a new server id for the same track keeps history, playlist entries and the download`() = runTest {
        val gong = track("x", "Gong", track = 4, sizeBytes = 100)
        syncer.syncAdding(db, FakeSource("nd", listOf(gong, track("y", "Hoppípolla", track = 3))))
        val songId = db.libraryDao().observeSongRows().first().single { it.title == "Gong" }.songId
        db.playbackDao().insertPlayEventIfSongExists(PlayEventEntity(songId = songId, playedAt = 1_000, listenedMs = 60_000))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "Mix", updatedAt = 1))
        db.playlistDao().insertEntries(listOf(PlaylistEntryEntity(playlistId = playlistId, songId = songId, position = 0)))
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:x", songId, DownloadState.DONE, filePath = "/dl/x.flac")))

        // The server moved the folder: same track, new ID.
        syncer.syncAdding(db, FakeSource("nd", listOf(gong.copy(remoteId = "x2"), track("y", "Hoppípolla", track = 3))))

        val songs = db.libraryDao().observeSongRows().first()
        assertEquals(2, songs.size)
        val newId = songs.single { it.title == "Gong" }.songId
        assertEquals(listOf("nd:x2"), db.mergeDao().getSourcesOfSong(newId).map { it.uid })
        db.openHelper.readableDatabase.query("SELECT songId FROM play_events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(newId, cursor.getLong(0))
        }
        assertEquals(listOf("Gong"), db.playlistDao().observeEntryRows(playlistId).first().map { it.song.title })
        val download = db.downloadDao().get("nd:x2")!!
        assertEquals(DownloadState.DONE, download.state)
        assertEquals(newId, download.songId)
    }

    @Test
    fun `a new server id for the same track keeps the removed download removed`() = runTest {
        val gong = track("x", "Gong", track = 4, sizeBytes = 100)
        syncer.syncAdding(db, FakeSource("nd", listOf(gong, track("y", "Hoppípolla", track = 3))))
        val songId = db.libraryDao().observeSongRows().first().single { it.title == "Gong" }.songId
        db.downloadDao().exclude(listOf(DownloadExclusionEntity(songId, excludedAt = 1_000)))

        syncer.syncAdding(db, FakeSource("nd", listOf(gong.copy(remoteId = "x2"), track("y", "Hoppípolla", track = 3))))

        val newId = db.libraryDao().observeSongRows().first().single { it.title == "Gong" }.songId
        assertEquals(listOf(newId), db.downloadDao().getExcluded(listOf(songId, newId)))
    }

    @Test
    fun `a removed track does not hand its history to a different track of the album`() = runTest {
        syncer.syncAdding(db, FakeSource("nd", listOf(track("x", "Gong", track = 4), track("y", "Hoppípolla", track = 3))))
        val songId = db.libraryDao().observeSongRows().first().single { it.title == "Gong" }.songId
        db.playbackDao().insertPlayEventIfSongExists(PlayEventEntity(songId = songId, playedAt = 1_000, listenedMs = 60_000))

        syncer.syncAdding(db, FakeSource("nd", listOf(track("y", "Hoppípolla", track = 3))))

        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM play_events").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `a song with several sources takes its genres from the source with the best quality`() = runTest {
        val mp3 = track("1", "Hoppípolla").copy(genre = "Rock", genres = listOf("Rock", "Pop"), mimeType = "audio/mpeg", bitRate = 320)
        val flac = mp3.copy(remoteId = "2", genre = "Post-Rock", genres = listOf("Post-Rock"), mimeType = "audio/flac", bitRate = 900)
        suspend fun genres() = db.libraryDao().observeGenreRows().first().map { it.name }

        // First the lower-quality source, then the lossless one: its genres apply.
        syncer.syncAdding(db, FakeSource("nd", listOf(mp3)))
        assertEquals(listOf("Pop", "Rock"), genres())
        syncer.syncAdding(db, FakeSource("jf", listOf(flac)))
        assertEquals(1, db.libraryDao().observeSongRows().first().size)
        assertEquals(listOf("Post-Rock"), genres())

        // Another, lower-quality source changes nothing.
        syncer.syncAdding(db, FakeSource("em", listOf(mp3.copy(remoteId = "3", genres = listOf("Schlager"), genre = "Schlager", bitRate = 128))))
        assertEquals(listOf("Post-Rock"), genres())
    }

    @Test
    fun `a song keeps all genres of its source and follows when they change`() = runTest {
        val walk = track("x", "Walk This Way").copy(genre = "Hip-Hop", genres = listOf("Hip-Hop", "Rap", "rap "))
        syncer.syncAdding(db, FakeSource("nd", listOf(walk)))

        val library = db.libraryDao()
        assertEquals(listOf("Hip-Hop", "Rap"), library.observeGenreRows().first().map { it.name })
        assertEquals(listOf("Walk This Way"), library.getSongRowsOfGenre(Genre.key("RAP")).map { it.title })
        assertEquals("Hip-Hop", db.mergeDao().getSong(library.observeSongRows().first().single().songId)!!.genre)

        // Retagged: only Rock now.
        syncer.syncAdding(db, FakeSource("nd", listOf(walk.copy(genre = "Rock", genres = listOf("Rock"), lastModified = 2))))
        assertEquals(listOf("Rock"), library.observeGenreRows().first().map { it.name })
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
            if (failAfterFirstBatch) error("Connection aborted")
        }

        override suspend fun resolvePlayback(remoteId: String) = PlaybackLocation.LocalFile(Uri.EMPTY)

        override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
    }
}
