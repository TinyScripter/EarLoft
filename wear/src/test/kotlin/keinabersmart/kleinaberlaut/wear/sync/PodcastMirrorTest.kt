package keinabersmart.kleinaberlaut.wear.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.download.BookStorage
import keinabersmart.kleinaberlaut.core.download.EpisodeStorage
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.wearsync.WearBookDto
import keinabersmart.kleinaberlaut.core.wearsync.WearBookFile
import keinabersmart.kleinaberlaut.core.wearsync.WearBookTrackDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeFile
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeProgressDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastFeedDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastManifest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Die Uhr übernimmt die gewählten Folgen und Bücher des Handys (Wear-Konzept 16.1/16.2). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PodcastMirrorTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var reported: ReportedEpisodes
    private lateinit var mirror: PodcastMirror
    private lateinit var storage: EpisodeStorage
    private lateinit var bookStorage: BookStorage

    private val feed = WearPodcastFeedDto("f1", "https://example.org/feed", "Podcast", newestFirst = true, continuous = false)
    private val local = WearPodcastFeedDto("local:1", "local:1", "Vom Handy")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reported = ReportedEpisodes(ApplicationProvider.getApplicationContext())
        storage = EpisodeStorage(ApplicationProvider.getApplicationContext())
        bookStorage = BookStorage(ApplicationProvider.getApplicationContext())
        mirror = PodcastMirror(db, db.podcastDao(), db.bookDao(), reported, storage, bookStorage)
    }

    @After
    fun tearDown() = db.close()

    private fun episode(guid: String, feedId: String = "f1", progress: WearEpisodeProgressDto? = null, audioUrl: String? = "https://example.org/$guid.mp3") =
        WearEpisodeDto(feedId = feedId, guid = guid, title = "Folge $guid", durationMs = 3_600_000, audioUrl = audioUrl, progress = progress)

    private suspend fun bookId(guid: String, feedId: String = "f1") = db.bookDao().bookByRemote(feedId, guid)?.bookId

    @Test
    fun `Abos und Folgen werden mit den Schluesseln des Handys angelegt`() = runTest {
        val ids = mirror.apply(WearPodcastManifest(listOf(feed, local), listOf(episode("b"), episode("a"), episode("x", "local:1", audioUrl = null))))

        assertEquals(listOf(bookId("b"), bookId("a"), bookId("x", "local:1")), ids)
        val stored = db.podcastDao().feed("f1")!!
        assertEquals("https://example.org/feed", stored.url)
        assertEquals(true, stored.newestFirst)
        assertEquals(false, stored.continuous)
        val book = db.bookDao().book(bookId("a")!!)!!
        assertEquals(BookKind.EPISODE, book.kind)
        assertEquals("Podcast", book.author)
        assertEquals("https://example.org/a.mp3", db.podcastDao().trackOf(book.bookId)!!.remoteFileId)
        // Folgen vom Gerät des Handys gibt es nur über den Datei-Kanal.
        assertEquals(WearEpisodeFile.uid("local:1", "x"), db.podcastDao().trackOf(bookId("x", "local:1")!!)!!.remoteFileId)
    }

    @Test
    fun `nicht mehr gelieferte Folgen und Abos verschwinden, gebliebene behalten ihren Download`() = runTest {
        mirror.apply(WearPodcastManifest(listOf(feed, local), listOf(episode("a"), episode("b"), episode("x", "local:1", audioUrl = null))))
        val a = bookId("a")!!
        val b = bookId("b")!!
        val fileA = download(a)
        val fileB = download(b)

        mirror.apply(WearPodcastManifest(listOf(feed), listOf(episode("a"))))

        assertEquals(a, bookId("a"))
        assertTrue(fileA.isFile)
        assertNull(bookId("b"))
        assertFalse(fileB.exists())
        assertNull(bookId("x", "local:1"))
        assertNull(db.podcastDao().feed("local:1"))
        assertEquals(TrackDownload.DONE, db.podcastDao().trackOf(a)!!.downloadState)
    }

    private suspend fun download(bookId: Long): File {
        val file = storage.fileFor(bookId, "audio/mpeg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10)) }
        db.podcastDao().queueDownload(bookId)
        db.podcastDao().markDownloadRunning(bookId)
        db.podcastDao().markDownloadDone(bookId, file.absolutePath, file.length())
        return file
    }

    private fun book(remoteId: String, serverId: String = "abs", files: List<String?> = listOf("$remoteId-0"), progress: WearEpisodeProgressDto? = null) =
        WearBookDto(
            serverId = serverId,
            remoteId = remoteId,
            title = "Buch $remoteId",
            author = "Autorin",
            durationMs = files.size * 3_600_000L,
            tracks = files.mapIndexed { index, fileId -> WearBookTrackDto(index, fileId, index * 3_600_000L, 3_600_000) },
            progress = progress,
        )

    @Test
    fun `Buecher werden mit Dateiliste und Fortschritt angelegt, nach den Folgen in der Reihenfolge`() = runTest {
        val ids = mirror.apply(
            WearPodcastManifest(
                listOf(feed),
                listOf(episode("a")),
                listOf(book("b1", files = listOf("f0", "f1"), progress = WearEpisodeProgressDto(4_000_000, 7_200_000, updatedAt = 7)), book("eigen", "local", listOf(null))),
            ),
        )

        val b1 = db.bookDao().bookByRemote("abs", "b1")!!
        val eigen = db.bookDao().bookByRemote("local", "eigen")!!
        assertEquals(listOf(bookId("a"), b1.bookId, eigen.bookId), ids)
        assertEquals(BookKind.BOOK, b1.kind)
        assertTrue(b1.detailsLoaded)
        assertEquals(listOf("f0", "f1"), db.bookDao().tracks(b1.bookId).map { it.remoteFileId })
        assertEquals(4_000_000, db.bookDao().progress(b1.bookId)!!.positionMs)
        assertEquals(7, reported.progressAt("abs", "b1"))
        // Bücher vom Gerät des Handys gibt es nur über den Datei-Kanal.
        assertEquals(WearBookFile.uid("local", "eigen", 0), db.bookDao().tracks(eigen.bookId).single().remoteFileId)
    }

    @Test
    fun `nicht mehr gelieferte Buecher verschwinden samt Dateien, gleiche Dateiliste behaelt den Download`() = runTest {
        mirror.apply(WearPodcastManifest(books = listOf(book("b1"), book("b2"))))
        val b1 = db.bookDao().bookByRemote("abs", "b1")!!.bookId
        val b2 = db.bookDao().bookByRemote("abs", "b2")!!.bookId
        val file1 = downloadBook(b1)
        val file2 = downloadBook(b2)

        mirror.apply(WearPodcastManifest(books = listOf(book("b1"))))

        assertEquals(b1, db.bookDao().bookByRemote("abs", "b1")!!.bookId)
        assertEquals(TrackDownload.DONE, db.bookDao().tracks(b1).single().downloadState)
        assertTrue(file1.isFile)
        assertNull(db.bookDao().bookByRemote("abs", "b2"))
        assertFalse(file2.exists())
    }

    private suspend fun downloadBook(bookId: Long): File {
        val file = bookStorage.fileFor(bookId, 0, "audio/mp4").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10)) }
        db.bookDao().queueBookDownload(bookId)
        db.bookDao().markBookTrackRunning(bookId, 0)
        db.bookDao().markBookTrackDone(bookId, 0, file.absolutePath, file.length())
        return file
    }

    @Test
    fun `Fortschritt des Handys gilt nur, wenn er neuer ist, und wird nicht zurueckgemeldet`() = runTest {
        mirror.apply(WearPodcastManifest(listOf(feed), listOf(episode("a"))))
        val a = bookId("a")!!
        db.bookDao().upsertProgress(BookProgressEntity(a, positionMs = 120_000, durationMs = 3_600_000, updatedAt = 5_000))

        mirror.apply(WearPodcastManifest(listOf(feed), listOf(episode("a", progress = WearEpisodeProgressDto(60_000, 3_600_000, updatedAt = 4_000)))))
        assertEquals(120_000, db.bookDao().progress(a)!!.positionMs)

        mirror.apply(WearPodcastManifest(listOf(feed), listOf(episode("a", progress = WearEpisodeProgressDto(900_000, 3_600_000, speed = 1.5f, updatedAt = 6_000)))))
        val progress = db.bookDao().progress(a)!!
        assertEquals(900_000, progress.positionMs)
        assertEquals(1.5f, progress.speed)
        assertEquals(6_000, reported.progressAt("f1", "a"))
    }
}
