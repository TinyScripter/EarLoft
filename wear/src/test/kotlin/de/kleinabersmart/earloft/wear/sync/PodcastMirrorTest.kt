package de.kleinabersmart.earloft.wear.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.wearsync.WearBookDto
import de.kleinabersmart.earloft.core.wearsync.WearBookFile
import de.kleinabersmart.earloft.core.wearsync.WearBookTrackDto
import de.kleinabersmart.earloft.core.wearsync.WearChapterDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeFile
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeProgressDto
import de.kleinabersmart.earloft.core.wearsync.WearPodcastFeedDto
import de.kleinabersmart.earloft.core.wearsync.WearPodcastManifest
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

/** The watch applies the phone's chosen episodes and books (Wear concept 16.1/16.2). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PodcastMirrorTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var reported: ReportedEpisodes
    private lateinit var mirror: PodcastMirror
    private lateinit var storage: EpisodeStorage
    private lateinit var bookStorage: BookStorage

    private val feed = WearPodcastFeedDto("f1", "https://example.org/feed", "Podcast", newestFirst = true, continuous = false)
    private val local = WearPodcastFeedDto("local:1", "local:1", "From the phone")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
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
        WearEpisodeDto(feedId = feedId, guid = guid, title = "Episode $guid", durationMs = 3_600_000, audioUrl = audioUrl, progress = progress)

    private suspend fun bookId(guid: String, feedId: String = "f1") = db.bookDao().bookByRemote(feedId, guid)?.bookId

    @Test
    fun `subscriptions and episodes are created with the keys of the phone`() = runTest {
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
        // Episodes from the phone's device are only available via the file channel.
        assertEquals(WearEpisodeFile.uid("local:1", "x"), db.podcastDao().trackOf(bookId("x", "local:1")!!)!!.remoteFileId)
    }

    @Test
    fun `episodes and subscriptions no longer delivered disappear, remaining ones keep their download`() = runTest {
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
            title = "Book $remoteId",
            author = "Author",
            durationMs = files.size * 3_600_000L,
            tracks = files.mapIndexed { index, fileId -> WearBookTrackDto(index, fileId, index * 3_600_000L, 3_600_000) },
            progress = progress,
        )

    @Test
    fun `books are created with file list and progress, after the episodes in order`() = runTest {
        val ids = mirror.apply(
            WearPodcastManifest(
                listOf(feed),
                listOf(episode("a")),
                listOf(book("b1", files = listOf("f0", "f1"), progress = WearEpisodeProgressDto(4_000_000, 7_200_000, updatedAt = 7)), book("own", "local", listOf(null))),
            ),
        )

        val b1 = db.bookDao().bookByRemote("abs", "b1")!!
        val own = db.bookDao().bookByRemote("local", "own")!!
        assertEquals(listOf(bookId("a"), b1.bookId, own.bookId), ids)
        assertEquals(BookKind.BOOK, b1.kind)
        assertTrue(b1.detailsLoaded)
        assertEquals(listOf("f0", "f1"), db.bookDao().tracks(b1.bookId).map { it.remoteFileId })
        assertEquals(4_000_000, db.bookDao().progress(b1.bookId)!!.positionMs)
        assertEquals(7, reported.progressAt("abs", "b1"))
        // Books from the phone's device are only available via the file channel.
        assertEquals(WearBookFile.uid("local", "own", 0), db.bookDao().tracks(own.bookId).single().remoteFileId)
    }

    @Test
    fun `books no longer delivered disappear with their files, same file list keeps the download`() = runTest {
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
    fun `chapters of a book follow the manifest`() = runTest {
        val chapters = listOf(WearChapterDto("One", 0, 1_800_000), WearChapterDto("Two", 1_800_000, 3_600_000))
        mirror.apply(WearPodcastManifest(books = listOf(book("b1").copy(chapters = chapters))))
        val b1 = db.bookDao().bookByRemote("abs", "b1")!!.bookId
        assertEquals(listOf("One" to 0L, "Two" to 1_800_000L), db.bookDao().chapters(b1).map { it.title to it.startMs })

        mirror.apply(WearPodcastManifest(books = listOf(book("b1").copy(chapters = chapters.take(1)))))
        assertEquals(listOf("One"), db.bookDao().chapters(b1).map { it.title })
    }

    @Test
    fun `chapters of an episode come along, without chapters from the phone the own ones stay`() = runTest {
        mirror.apply(
            WearPodcastManifest(
                listOf(feed),
                listOf(episode("a").copy(chapters = listOf(WearChapterDto("Intro", 0, 60_000)), chaptersUrl = "https://example.org/a.json")),
            ),
        )
        val a = bookId("a")!!
        assertEquals(listOf("Intro"), db.bookDao().chapters(a).map { it.title })
        assertEquals("https://example.org/a.json", db.bookDao().book(a)!!.chaptersUrl)

        // The phone does not know the chapters (yet), but the watch already has them: they stay.
        mirror.apply(WearPodcastManifest(listOf(feed), listOf(episode("a"))))
        assertEquals(listOf("Intro"), db.bookDao().chapters(a).map { it.title })
    }

    @Test
    fun `progress from the phone only applies if it is newer and is not reported back`() = runTest {
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
