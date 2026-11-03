package keinabersmart.kleinaberlaut.wear.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeFile
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeProgressDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastFeedDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastManifest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Die Uhr übernimmt die Folgen aus „Weiterhören“ des Handys (Wear-Konzept 16.1). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PodcastMirrorTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var reported: ReportedEpisodes
    private lateinit var mirror: PodcastMirror

    private val feed = WearPodcastFeedDto("f1", "https://example.org/feed", "Podcast", newestFirst = true, continuous = false)
    private val local = WearPodcastFeedDto("local:1", "local:1", "Vom Handy")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reported = ReportedEpisodes(ApplicationProvider.getApplicationContext())
        mirror = PodcastMirror(db, db.podcastDao(), db.bookDao(), reported)
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
        db.podcastDao().queueDownload(a)
        db.podcastDao().markDownloadRunning(a)
        db.podcastDao().markDownloadDone(a, "/data/episode-$a.mp3", 1_000)

        mirror.apply(WearPodcastManifest(listOf(feed), listOf(episode("a"))))

        assertEquals(a, bookId("a"))
        assertNull(bookId("b"))
        assertNull(bookId("x", "local:1"))
        assertNull(db.podcastDao().feed("local:1"))
        assertEquals(TrackDownload.DONE, db.podcastDao().trackOf(a)!!.downloadState)
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
