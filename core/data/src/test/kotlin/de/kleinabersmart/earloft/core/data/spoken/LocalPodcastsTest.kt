package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.sync.SweepGuard
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookChapter
import de.kleinabersmart.earloft.core.datasource.local.LocalBookTrack
import de.kleinabersmart.earloft.core.datasource.local.LocalEpisode
import de.kleinabersmart.earloft.core.datasource.local.LocalEpisodeDetails
import de.kleinabersmart.earloft.core.datasource.local.LocalPodcast
import de.kleinabersmart.earloft.core.datasource.local.LocalScan
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Podcast files on the device against a real database; only the MediaStore scan is replaced by a list of files. */
@RunWith(RobolectricTestRunner::class)
class LocalPodcastsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EarLoftDatabase
    private lateinit var settings: SpokenSettings
    private lateinit var podcasts: LocalPodcasts

    /** The files the next scan sees: podcast name → episodes. */
    private var files: Map<String, List<File>> = emptyMap()
    private var complete = true
    private var failure: Exception? = null

    /** Episode keys the scan had to read (tags, chapters) because they were new or changed. */
    private val read = mutableListOf<String>()
    private val invalidatedCovers = mutableListOf<Long>()

    private data class File(val key: String, val fingerprint: Long, val title: String = key, val publishedAtMs: Long = 0, val hasCover: Boolean = false)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        settings = SpokenSettings(context)
        settings.setUseLocalPodcasts(true)
        podcasts = LocalPodcasts(db.bookDao(), db.podcastDao(), ::scan, settings, { invalidatedCovers += it }, SweepGuard(context))
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Like [de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner.scanPodcasts]: unchanged files are not read. */
    private suspend fun scan(isUnchanged: suspend (String, String, Long) -> Boolean): LocalScan<LocalPodcast> {
        failure?.let { throw it }
        val items = files.map { (name, episodes) ->
            LocalPodcast(
                name,
                episodes.map { file ->
                    val details = if (isUnchanged(name, file.key, file.fingerprint)) {
                        null
                    } else {
                        read += file.key
                        LocalEpisodeDetails(
                            title = file.title,
                            description = "About ${file.title}",
                            publishedAtMs = file.publishedAtMs,
                            track = LocalBookTrack("content://media/${file.key}", 0, 60_000, "audio/mpeg"),
                            chapters = listOf(LocalBookChapter("Intro", 0, 10_000), LocalBookChapter("Main", 10_000, 60_000)),
                            hasCover = file.hasCover,
                        )
                    }
                    LocalEpisode(file.key, file.fingerprint, details)
                },
            )
        }
        return LocalScan(items, complete)
    }

    private suspend fun localFeeds() = db.podcastDao().feeds().filter { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }

    private suspend fun episode(key: String) = localFeeds().firstNotNullOfOrNull { db.bookDao().bookByRemote(it.feedId, key) }

    @Test
    fun `new files become a podcast with playable episodes`() = runTest {
        files = mapOf("Morning Talk" to listOf(File("Morning Talk/1.mp3", 1, "First"), File("Morning Talk/2.mp3", 1, "Second")))

        assertTrue(podcasts.sync())

        val feed = localFeeds().single()
        assertEquals("Morning Talk", feed.title)
        assertEquals(feed.feedId, feed.url)
        val first = episode("Morning Talk/1.mp3")!!
        assertEquals(BookKind.EPISODE, first.kind)
        assertEquals("First", first.title)
        assertEquals("Morning Talk", first.author)
        assertTrue(first.detailsLoaded)
        assertEquals(listOf("content://media/Morning Talk/1.mp3"), db.bookDao().tracks(first.bookId).map { it.remoteFileId })
        assertEquals(listOf("Intro", "Main"), db.bookDao().chapters(first.bookId).map { it.title })
        assertEquals(2, invalidatedCovers.size)
    }

    @Test
    fun `the same name in another spelling is the same podcast`() = runTest {
        files = mapOf("Morning Talk" to listOf(File("a.mp3", 1)))
        podcasts.sync()
        files = mapOf(" morning talk " to listOf(File("a.mp3", 1)))
        podcasts.sync()

        assertEquals(1, localFeeds().size)
    }

    @Test
    fun `unchanged files are not read again, changed ones are`() = runTest {
        files = mapOf("Talk" to listOf(File("1.mp3", 1, "First"), File("2.mp3", 1, "Second")))
        podcasts.sync()
        read.clear()

        files = mapOf("Talk" to listOf(File("1.mp3", 1, "First"), File("2.mp3", 2, "Second, new cut")))
        podcasts.sync()

        assertEquals(listOf("2.mp3"), read)
        assertEquals("First", episode("1.mp3")!!.title)
        assertEquals("Second, new cut", episode("2.mp3")!!.title)
    }

    @Test
    fun `a deleted file takes its progress, a podcast without files goes`() = runTest {
        files = mapOf(
            "Talk" to listOf(File("1.mp3", 1), File("2.mp3", 1), File("3.mp3", 1)),
            "News" to listOf(File("n.mp3", 1)),
        )
        podcasts.sync()
        val gone = episode("2.mp3")!!.bookId
        db.bookDao().upsertProgress(BookProgressEntity(gone, positionMs = 5_000, durationMs = 60_000, updatedAt = 1))

        files = mapOf("Talk" to listOf(File("1.mp3", 1), File("3.mp3", 1)))
        assertTrue(podcasts.sync())

        assertNull(episode("2.mp3"))
        assertNull(db.bookDao().progress(gone))
        assertNotNull(episode("1.mp3"))
        assertEquals(listOf("Talk"), localFeeds().map { it.title })
    }

    @Test
    fun `a missing storage volume removes nothing`() = runTest {
        files = mapOf("Talk" to listOf(File("1.mp3", 1), File("2.mp3", 1)), "News" to listOf(File("n.mp3", 1)))
        podcasts.sync()

        files = mapOf("Talk" to listOf(File("1.mp3", 1)))
        complete = false
        assertTrue(podcasts.sync())

        assertNotNull(episode("2.mp3"))
        assertEquals(setOf("Talk", "News"), localFeeds().mapTo(HashSet()) { it.title })
    }

    @Test
    fun `everything gone at once waits for a second look`() = runTest {
        files = mapOf("Talk" to listOf(File("1.mp3", 1), File("2.mp3", 1)))
        podcasts.sync()

        // The media index being rebuilt after an update looks like this.
        files = emptyMap()
        assertTrue(podcasts.sync())

        assertNotNull(episode("1.mp3"))
        assertEquals(1, localFeeds().size)
    }

    @Test
    fun `without permission or with a read error nothing changes`() = runTest {
        files = mapOf("Talk" to listOf(File("1.mp3", 1)))
        podcasts.sync()

        files = emptyMap()
        failure = SecurityException("no audio permission")
        assertFalse(podcasts.sync())
        failure = IOException("media store gone")
        assertFalse(podcasts.sync())

        assertNotNull(episode("1.mp3"))
    }

    @Test
    fun `switched off, the folder is not scanned`() = runTest {
        settings.setUseLocalPodcasts(false)
        files = mapOf("Talk" to listOf(File("1.mp3", 1)))

        assertTrue(podcasts.sync())

        assertTrue(read.isEmpty())
        assertTrue(localFeeds().isEmpty())
    }

    @Test
    fun `remove drops the podcasts on the device but keeps the feeds`() = runTest {
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = "rss-1", url = "https://example.org/feed.xml", title = "Subscribed"))
        files = mapOf("Talk" to listOf(File("1.mp3", 1)))
        podcasts.sync()
        val bookId = episode("1.mp3")!!.bookId

        podcasts.remove()

        assertTrue(localFeeds().isEmpty())
        assertNull(db.bookDao().book(bookId))
        assertEquals(listOf("rss-1"), db.podcastDao().feeds().map { it.feedId })
    }

    @Test
    fun `the podcast image is the newest episode cover and stays while that episode is there`() = runTest {
        files = mapOf(
            "Talk" to listOf(
                File("old.mp3", 1, publishedAtMs = 1_000, hasCover = true),
                File("new.mp3", 1, publishedAtMs = 2_000, hasCover = true),
                File("newest-no-cover.mp3", 1, publishedAtMs = 3_000),
            ),
        )
        podcasts.sync()
        val newest = episode("new.mp3")!!.bookId
        assertEquals(AppUris.bookCover(newest), localFeeds().single().imageUrl)

        // A newer episode with a cover arrives: the image stays as long as its episode is there.
        files = mapOf("Talk" to files.getValue("Talk") + File("newer.mp3", 1, publishedAtMs = 4_000, hasCover = true))
        podcasts.sync()
        assertEquals(AppUris.bookCover(newest), localFeeds().single().imageUrl)

        // Its episode is deleted while the other files are unchanged (not read again): the newest remaining one takes over.
        files = mapOf("Talk" to files.getValue("Talk").filterNot { it.key == "new.mp3" })
        read.clear()
        podcasts.sync()
        assertTrue(read.isEmpty())
        assertEquals(AppUris.bookCover(episode("newer.mp3")!!.bookId), localFeeds().single().imageUrl)
    }

    @Test
    fun `a podcast without any cover has no image`() = runTest {
        files = mapOf("Talk" to listOf(File("1.mp3", 1), File("2.mp3", 1)))
        podcasts.sync()
        files = mapOf("Talk" to listOf(File("1.mp3", 1)))
        podcasts.sync()

        assertNull(localFeeds().single().imageUrl)
    }
}
