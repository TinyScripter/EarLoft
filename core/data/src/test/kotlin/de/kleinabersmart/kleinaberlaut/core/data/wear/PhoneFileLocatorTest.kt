package de.kleinabersmart.kleinaberlaut.core.data.wear

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.kleinaberlaut.core.data.security.SecretStore
import de.kleinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import de.kleinabersmart.kleinaberlaut.core.data.sync.FakeServer
import de.kleinabersmart.kleinaberlaut.core.data.sync.FixedSources
import de.kleinabersmart.kleinaberlaut.core.data.sync.LibrarySyncer
import de.kleinabersmart.kleinaberlaut.core.data.sync.librarySyncer
import de.kleinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import de.kleinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import de.kleinabersmart.kleinaberlaut.core.datasource.RawArtist
import de.kleinabersmart.kleinaberlaut.core.datasource.local.LocalBookScanner
import de.kleinabersmart.kleinaberlaut.core.datasource.RawTrack
import de.kleinabersmart.kleinaberlaut.core.datasource.SourceBatch
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.model.DownloadState
import de.kleinabersmart.kleinaberlaut.core.model.TrackDownload
import de.kleinabersmart.kleinaberlaut.core.model.SourceType
import de.kleinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
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
    private lateinit var covers: BookCovers

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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient()
        val api = AbsApi(client)
        // Ohne Schlüssel ist kein Server eingetragen; Bilder kommen in den Tests über `store`.
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val servers = AudiobookshelfServers(db.bookDao(), secrets, api, AbsServerRegistry())
        covers = BookCovers(context, db.bookDao(), db.podcastDao(), servers, api, PodcastFeedApi(client), LocalBookScanner(context))
        locator = PhoneFileLocator(context, db.wearDao(), FixedSources(nd, local), db.bookDao(), db.podcastDao(), covers)
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

    @Test
    fun `cover of an episode or book is sent, unknown ones give nothing`() = runTest {
        val id = episode("g1")
        covers.store(id, byteArrayOf(1, 2, 3))

        val file = locator.openCover("f1", "g1")!!

        assertEquals(3, file.length)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.input.use { it.readBytes() })
        assertNull(locator.openCover("f1", "gibtsnicht"))
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
