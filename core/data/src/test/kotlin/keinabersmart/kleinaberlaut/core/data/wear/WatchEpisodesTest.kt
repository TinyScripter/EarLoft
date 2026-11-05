package keinabersmart.kleinaberlaut.core.data.wear

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.entity.BookEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import keinabersmart.kleinaberlaut.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchEpisodesTest {

    private lateinit var db: KleinaberlautDatabase
    private lateinit var episodes: WatchEpisodes

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KleinaberlautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient()
        val podcasts = PodcastRepository(db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client))
        episodes = WatchEpisodes(db, db.wearDao(), db.podcastDao(), db.bookDao(), podcasts)
        db.podcastDao().upsertFeed(PodcastFeedEntity("f1", "https://example.org/feed", "Podcast", imageUrl = "https://example.org/bild.jpg"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `only chosen episodes go to the watch, in the order they were chosen`() = runTest {
        val first = episode("g1")
        episode("g2")
        val third = episode("g3")
        db.bookDao().upsertProgress(BookProgressEntity(third, positionMs = 60_000, durationMs = 600_000, updatedAt = 5))

        episodes.setSelected(third, selected = true)
        Thread.sleep(5)
        episodes.setSelected(first, selected = true)
        // Erneutes Wählen behält den ersten Zeitpunkt.
        episodes.setSelected(third, selected = true)

        val content = episodes.load()
        assertEquals(listOf("g3", "g1"), content.episodes.map { it.guid })
        assertEquals(listOf("f1"), content.feeds.map { it.feedId })
        assertEquals(60_000L, content.episodes.first().progress?.positionMs)
        assertEquals("https://example.org/g1.mp3", content.episodes.last().audioUrl)
        assertEquals("https://example.org/bild.jpg", content.episodes.last().coverUrl)
        assertEquals(setOf(first, third), episodes.selectedIds().first())

        episodes.setSelected(third, selected = false)
        assertEquals(listOf("g1"), episodes.load().episodes.map { it.guid })
    }

    @Test
    fun `a finished episode leaves the watch, choosing it again reopens it`() = runTest {
        val id = episode("g1")
        episodes.setSelected(id, selected = true)

        db.bookDao().upsertProgress(BookProgressEntity(id, positionMs = 600_000, durationMs = 600_000, finished = true, updatedAt = 9))

        assertTrue(episodes.load().episodes.isEmpty())
        assertTrue(episodes.selectedIds().first().isEmpty())

        episodes.setSelected(id, selected = true)

        val content = episodes.load()
        assertEquals(listOf("g1"), content.episodes.map { it.guid })
        assertFalse(content.episodes.single().progress!!.finished)
    }

    @Test
    fun `archived feeds stay off the watch, deleted episodes leave the selection`() = runTest {
        val id = episode("g1")
        episodes.setSelected(id, selected = true)

        db.podcastDao().upsertFeed(db.podcastDao().feed("f1")!!.copy(archived = true))
        assertTrue(episodes.load().episodes.isEmpty())
        assertEquals(setOf(id), episodes.selectedIds().first())

        db.bookDao().deleteBooksOf("f1")
        assertTrue(episodes.selectedIds().first().isEmpty())
    }

    @Test
    fun `clear removes all chosen episodes`() = runTest {
        episodes.setSelected(episode("g1"), selected = true)
        episodes.setSelected(episode("g2"), selected = true)

        episodes.clear()

        assertTrue(episodes.load().episodes.isEmpty())
    }

    private suspend fun episode(guid: String): Long {
        val id = db.bookDao().insertBook(
            BookEntity(serverId = "f1", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "Podcast", durationMs = 600_000),
        )
        db.podcastDao().insertTracks(listOf(BookTrackEntity(id, 0, "https://example.org/$guid.mp3", 0, 600_000, mimeType = "audio/mpeg")))
        return id
    }
}
