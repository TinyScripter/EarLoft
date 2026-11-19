package de.kleinabersmart.earloft.core.data.share

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.podcast.FeedAuth
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthRegistry
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    private lateinit var db: EarLoftDatabase
    private lateinit var repository: ShareRepository
    private val feedAuth = FeedAuthRegistry()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ShareRepository(db.libraryDao(), db.podcastDao(), db.bookDao(), db.sourceDao(), feedAuth)
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
    fun `a private feed address with its token is left out, the website stays`() = runTest {
        feed("a", "https://www.patreon.com/rss/show?auth=secret", website = "https://example.com/")
        assertEquals("Podcast a\nhttps://example.com/", repository.content(ShareTarget.Feed("a"))?.text)
    }

    @Test
    fun `a protected feed shares neither its address nor audio on its host`() = runTest {
        feed("a", "https://members.example.com/feed.xml", website = "https://example.com/")
        val onHost = episode("a", "One", null, "https://members.example.com/1.mp3")
        val onCdn = episode("a", "Two", null, "https://cdn.example.com/2.mp3")
        feedAuth.replaceAll(mapOf("a" to FeedAuth("https://members.example.com/feed.xml".toHttpUrl(), "user", "pass")))

        assertEquals("Podcast a\nhttps://example.com/", repository.content(ShareTarget.Feed("a"))?.text)
        assertEquals("One\nPodcast a", repository.content(ShareTarget.Book(onHost))?.text)
        assertEquals("Two\nPodcast a\nhttps://cdn.example.com/2.mp3", repository.content(ShareTarget.Book(onCdn))?.text)
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
        val id = episode("local:x", "File", null, "content://media/1")
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
        val withPage = episode("a", "One", "https://example.com/1", "https://cdn.example.com/1.mp3")
        val withoutPage = episode("a", "Two", "https://open.spotify.com/episode/2", "https://cdn.example.com/2.mp3")
        assertEquals(
            "One\nPodcast a\nhttps://example.com/1\nRSS: https://example.com/feed.xml",
            repository.content(ShareTarget.Book(withPage))?.text,
        )
        assertEquals(
            "Two\nPodcast a\nhttps://cdn.example.com/2.mp3\nRSS: https://example.com/feed.xml",
            repository.content(ShareTarget.Book(withoutPage))?.text,
        )
    }

    @Test
    fun `sharing from a position names it and marks the audio link`() = runTest {
        feed("a", "https://example.com/feed.xml")
        val withPage = episode("a", "One", "https://example.com/1", "https://cdn.example.com/1.mp3")
        val withoutPage = episode("a", "Two", "https://open.spotify.com/episode/2", "https://cdn.example.com/2.mp3")
        val at = SharePosition(754_321, "from 12:34")
        assertEquals(
            "One\nPodcast a\nfrom 12:34\nhttps://example.com/1\nRSS: https://example.com/feed.xml",
            repository.content(ShareTarget.Book(withPage, at))?.text,
        )
        assertEquals(
            "Two\nPodcast a\nfrom 12:34\nhttps://cdn.example.com/2.mp3#t=754\nRSS: https://example.com/feed.xml",
            repository.content(ShareTarget.Book(withoutPage, at))?.text,
        )
    }
}
