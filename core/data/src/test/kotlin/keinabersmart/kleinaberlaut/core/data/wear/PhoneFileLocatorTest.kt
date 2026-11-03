package keinabersmart.kleinaberlaut.core.data.wear

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.data.sync.FakeServer
import keinabersmart.kleinaberlaut.core.data.sync.FixedSources
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySyncer
import keinabersmart.kleinaberlaut.core.data.sync.librarySyncer
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.datasource.RawArtist
import keinabersmart.kleinaberlaut.core.datasource.RawTrack
import keinabersmart.kleinaberlaut.core.datasource.SourceBatch
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.DownloadState
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PhoneFileLocatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: KleinaberlautDatabase
    private lateinit var syncer: LibrarySyncer
    private lateinit var locator: PhoneFileLocator
    private lateinit var localFile: File

    private val nd = FakeServer("nd", listOf(track("a", "A", 1), track("b", "B", 2)))

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        syncer = db.librarySyncer()
        localFile = folder.newFile("lokal.mp3").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        // „A“ gibt es auch lokal (wird mit dem Server-Song zusammengeführt), „Sommerlied“ nur lokal.
        val local = FileLocalSource(listOf(track("1", "A", 1), track("2", "Sommerlied", 1, album = "Lokal")), localFile)
        locator = PhoneFileLocator(ApplicationProvider.getApplicationContext(), db.wearDao(), FixedSources(nd, local), db.bookDao(), db.podcastDao())
        syncer.sync(nd)
        syncer.sync(local)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `local file is sent from the server uid of the same song`() = runTest {
        val file = locator.open("nd:a")!!

        assertEquals(300_000, file.length)
        assertArrayEquals(localFile.readBytes(), file.input.use { it.readBytes() })
    }

    @Test
    fun `song only on the phone is found by its local uid`() = runTest {
        assertEquals(300_000, locator.open("local:2")!!.length)
    }

    @Test
    fun `finished download is sent when there is no local file`() = runTest {
        val download = folder.newFile("nd_b.flac").apply { writeBytes(ByteArray(1_234) { 7 }) }
        val songId = db.downloadDao().getSongSource("nd:b")!!.songId
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("nd:b", songId, DownloadState.DONE, filePath = download.absolutePath)))

        val file = locator.open("nd:b")!!

        assertEquals(1_234, file.length)
        assertArrayEquals(download.readBytes(), file.input.use { it.readBytes() })
    }

    @Test
    fun `missing download file and unknown sources give nothing`() = runTest {
        val songId = db.downloadDao().getSongSource("nd:b")!!.songId
        db.downloadDao().insertIfAbsent(
            listOf(DownloadEntity("nd:b", songId, DownloadState.DONE, filePath = File(folder.root, "weg.flac").absolutePath)),
        )

        assertNull(locator.open("nd:b"))
        assertNull(locator.open("nd:gibtsnicht"))
    }

    @Test
    fun `downloaded episode is sent, an episode only in the feed is not`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity("f1", "https://example.org/feed", "Podcast"))
        val loaded = episode("g1")
        val streamOnly = episode("g2")
        val file = folder.newFile("episode.mp3").apply { writeBytes(ByteArray(4_321) { 3 }) }
        db.podcastDao().insertTracks(
            listOf(
                BookTrackEntity(loaded, 0, "https://example.org/g1.mp3", 0, 1_000, downloadState = TrackDownload.DONE, filePath = file.absolutePath),
                BookTrackEntity(streamOnly, 0, "https://example.org/g2.mp3", 0, 1_000),
            ),
        )

        assertArrayEquals(file.readBytes(), locator.openEpisode("f1", "g1")!!.input.use { it.readBytes() })
        assertNull(locator.openEpisode("f1", "g2"))
        assertNull(locator.openEpisode("f1", "gibtsnicht"))
    }

    private suspend fun episode(guid: String): Long = db.bookDao().insertBook(
        BookEntity(serverId = "f1", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "Podcast", durationMs = 1_000),
    )

    private fun track(remoteId: String, title: String, trackNumber: Int, album: String = "Album") = RawTrack(
        remoteId = remoteId,
        title = title,
        artistDisplay = "Band",
        artists = listOf(RawArtist("Band")),
        albumTitle = album,
        albumArtistDisplay = "Band",
        albumArtists = listOf(RawArtist("Band")),
        trackNumber = trackNumber,
        durationMs = 100_000,
        mimeType = "audio/mpeg",
        sizeBytes = 300_000,
        lastModified = 1,
    )

    /** Lokale Quelle, deren Songs alle auf dieselbe echte Datei zeigen. */
    private class FileLocalSource(private val tracks: List<RawTrack>, private val file: File) : MusicDataSource {
        override val sourceId = "local"
        override val type = SourceType.LOCAL
        override val displayName = "local"
        override fun fullScan(): Flow<SourceBatch> = flowOf(SourceBatch(tracks))
        override suspend fun resolvePlayback(remoteId: String) = PlaybackLocation.LocalFile(Uri.fromFile(file))
        override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
    }
}
