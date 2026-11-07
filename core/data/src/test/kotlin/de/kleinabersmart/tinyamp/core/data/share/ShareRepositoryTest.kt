package de.kleinabersmart.tinyamp.core.data.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.entity.BookEntity
import de.kleinabersmart.tinyamp.core.database.entity.BookTrackEntity
import de.kleinabersmart.tinyamp.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.tinyamp.core.model.BookKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareRepositoryTest {

    private lateinit var db: TinyAmpDatabase
    private lateinit var repository: ShareRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TinyAmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ShareRepository(db.libraryDao(), db.podcastDao(), db.bookDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun feed(id: String, url: String, website: String? = null) =
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = id, url = url, title = "Podcast $id", websiteUrl = website))

    private suspend fun episode(feedId: String, title: String, webUrl: String?, audio: String): Long {
        val id = db.bookDao().upsertBook(
            BookEntity(serverId = feedId, kind = BookKind.EPISODE, remoteId = title, title = title, normTitle = title, author = "", durationMs = 0, webUrl = webUrl),
        )
        db.podcastDao().setTrack(BookTrackEntity(id, 0, audio, 0, 0, "audio/mpeg"))
        return id
    }

    @Test
    fun `a feed shares its website and the feed address`() = runTest {
        feed("a", "https://example.com/feed.xml", website = "https://example.com/")
        assertEquals(
            ShareContent("Podcast a", "Podcast a\nhttps://example.com/\nRSS: https://example.com/feed.xml"),
            repository.content(ShareTarget.Feed("a")),
        )
    }

    @Test
    fun `a platform website is left out`() = runTest {
        feed("a", "https://example.com/feed.xml", website = "https://podcasts.apple.com/de/podcast/id1")
        assertEquals("Podcast a\nRSS: https://example.com/feed.xml", repository.content(ShareTarget.Feed("a"))?.text)
    }

    @Test
    fun `an ard sounds show shares its page`() = runTest {
        feed("a", "ardsounds:urn:ard:show:1")
        assertEquals("Podcast a\nhttps://www.ardsounds.de/sendung/urn:ard:show:1/", repository.content(ShareTarget.Feed("a"))?.text)
    }

    @Test
    fun `podcasts from the device are not shared`() = runTest {
        feed("local:x", "local:x")
        val id = episode("local:x", "Datei", null, "content://media/1")
        assertNull(repository.content(ShareTarget.Feed("local:x")))
        assertNull(repository.content(ShareTarget.Book(id)))
    }

    @Test
    fun `a book shares its open library page`() = runTest {
        val id = db.bookDao().upsertBook(
            BookEntity(serverId = "abs", remoteId = "li_1", title = "Momo", normTitle = "momo", author = "Michael Ende", durationMs = 0, isbn = "9783522202107"),
        )
        assertEquals(
            ShareContent("Momo – Michael Ende", "Momo – Michael Ende\nhttps://openlibrary.org/isbn/9783522202107"),
            repository.content(ShareTarget.Book(id)),
        )
    }

    @Test
    fun `an episode shares its page, otherwise the audio file`() = runTest {
        feed("a", "https://example.com/feed.xml")
        val withPage = episode("a", "Eins", "https://example.com/1", "https://cdn.example.com/1.mp3")
        val withoutPage = episode("a", "Zwei", "https://open.spotify.com/episode/2", "https://cdn.example.com/2.mp3")
        assertEquals(
            "Eins\nPodcast a\nhttps://example.com/1\nRSS: https://example.com/feed.xml",
            repository.content(ShareTarget.Book(withPage))?.text,
        )
        assertEquals(
            "Zwei\nPodcast a\nhttps://cdn.example.com/2.mp3\nRSS: https://example.com/feed.xml",
            repository.content(ShareTarget.Book(withoutPage))?.text,
        )
    }
}
