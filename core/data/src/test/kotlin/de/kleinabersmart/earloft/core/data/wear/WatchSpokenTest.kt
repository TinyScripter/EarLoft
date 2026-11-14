package de.kleinabersmart.earloft.core.data.wear

import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsBooks
import de.kleinabersmart.earloft.core.data.podcast.testFeedCredentials
import de.kleinabersmart.earloft.core.data.spoken.testAudiobookshelfServers
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookSyncer
import de.kleinabersmart.earloft.core.data.spoken.Bookmarks
import de.kleinabersmart.earloft.core.data.spoken.LocalBooks
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookChapterEntity
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchSpokenTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var spoken: WatchSpoken

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient()
        val api = AbsApi(client)
        // Ohne Schlüssel ist kein Server eingetragen: Abrufe und Meldungen scheitern sofort, ohne Netz.
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val dao = db.bookDao()
        val servers = testAudiobookshelfServers(dao, secrets, api)
        val progressSync = BookProgressSync(context, dao, servers, api, PlaybackPolicy())
        val scanner = LocalBookScanner(context)
        val covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), scanner)
        val syncer = BookSyncer(dao, servers, api, progressSync, Bookmarks(context, dao, servers, api, PlaybackPolicy()), LocalBooks(dao, scanner, SpokenSettings(context), covers),
            ArdBooks(dao, ArdSoundsBooks(ArdSoundsApi(OkHttpClient())), SpokenSettings(context), covers))
        val podcasts = PodcastRepository(db, db.podcastDao(), dao, PodcastFeedApi(client), ArdSoundsApi(client), PodcastSettings(ApplicationProvider.getApplicationContext()), AudioFileChapters(ApplicationProvider.getApplicationContext()), { error("kein Quellenwechsel im Test") }, testFeedCredentials(db))
        spoken = WatchSpoken(db, db.wearDao(), db.podcastDao(), dao, podcasts, syncer, progressSync)
        db.podcastDao().upsertFeed(PodcastFeedEntity("f1", "https://example.org/feed", "Podcast", imageUrl = "https://example.org/bild.jpg"))
        dao.upsertServer(SpokenServerEntity("abs", "Hörbücher", "https://abs.example.org"))
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

        spoken.setSelected(third, selected = true)
        Thread.sleep(5)
        spoken.setSelected(first, selected = true)
        // Erneutes Wählen behält den ersten Zeitpunkt.
        spoken.setSelected(third, selected = true)

        val content = spoken.load()
        assertEquals(listOf("g3", "g1"), content.episodes.map { it.guid })
        assertEquals(listOf("f1"), content.feeds.map { it.feedId })
        assertEquals(60_000L, content.episodes.first().progress?.positionMs)
        assertEquals("https://example.org/g1.mp3", content.episodes.last().audioUrl)
        assertEquals("https://example.org/bild.jpg", content.episodes.last().coverUrl)
        assertEquals(setOf(first, third), spoken.selectedIds().first())

        spoken.setSelected(third, selected = false)
        assertEquals(listOf("g1"), spoken.load().episodes.map { it.guid })
    }

    @Test
    fun `a finished episode leaves the watch, choosing it again reopens it`() = runTest {
        val id = episode("g1")
        spoken.setSelected(id, selected = true)

        db.bookDao().upsertProgress(BookProgressEntity(id, positionMs = 600_000, durationMs = 600_000, finished = true, updatedAt = 9))

        assertTrue(spoken.load().episodes.isEmpty())
        assertTrue(spoken.selectedIds().first().isEmpty())

        spoken.setSelected(id, selected = true)

        val content = spoken.load()
        assertEquals(listOf("g1"), content.episodes.map { it.guid })
        assertFalse(content.episodes.single().progress!!.finished)
    }

    @Test
    fun `archived feeds stay off the watch, deleted episodes leave the selection`() = runTest {
        val id = episode("g1")
        spoken.setSelected(id, selected = true)

        db.podcastDao().upsertFeed(db.podcastDao().feed("f1")!!.copy(archived = true))
        assertTrue(spoken.load().episodes.isEmpty())
        assertEquals(setOf(id), spoken.selectedIds().first())

        db.bookDao().deleteBooksOf("f1")
        assertTrue(spoken.selectedIds().first().isEmpty())
    }

    @Test
    fun `the watch deselects an episode or book by feed or server and id`() = runTest {
        val episode = episode("g1")
        val book = book("b1")
        spoken.setSelected(episode, selected = true)
        spoken.setSelected(book, selected = true)

        spoken.setSelected("f1", "g1", selected = false)
        spoken.setSelected("abs", "unbekannt", selected = false)

        assertEquals(setOf(book), spoken.selectedIds().first())
        spoken.setSelected("abs", "b1", selected = false)
        assertTrue(spoken.selectedIds().first().isEmpty())
    }

    @Test
    fun `clear removes all chosen episodes and books`() = runTest {
        spoken.setSelected(episode("g1"), selected = true)
        spoken.setSelected(book("b1"), selected = true)

        spoken.clear()

        val content = spoken.load()
        assertTrue(content.episodes.isEmpty())
        assertTrue(content.books.isEmpty())
    }

    @Test
    fun `clear by kind removes only episodes or only books`() = runTest {
        val episode = episode("g1")
        val book = book("b1")
        spoken.setSelected(episode, selected = true)
        spoken.setSelected(book, selected = true)

        spoken.clear(BookKind.EPISODE)
        assertEquals(setOf(book), spoken.selectedIds().first())

        spoken.setSelected(episode, selected = true)
        spoken.clear(BookKind.BOOK)
        assertEquals(setOf(episode), spoken.selectedIds().first())
    }

    @Test
    fun `a chosen book goes with all its files and its progress`() = runTest {
        val id = book("b1", files = 2)
        db.bookDao().upsertProgress(BookProgressEntity(id, positionMs = 4_000_000, durationMs = 7_200_000, updatedAt = 3))

        spoken.setSelected(id, selected = true)

        val book = spoken.load().books.single()
        assertEquals("abs", book.serverId)
        assertEquals("b1", book.remoteId)
        assertEquals("Autorin", book.author)
        assertEquals(listOf("b1-0", "b1-1"), book.tracks.map { it.fileId })
        assertEquals(listOf(0L, 3_600_000L), book.tracks.map { it.startMs })
        assertEquals(4_000_000L, book.progress?.positionMs)
    }

    @Test
    fun `chapters of books and episodes go along`() = runTest {
        val bookId = book("b1")
        val episodeId = episode("g1")
        db.bookDao().insertChapters(
            listOf(
                BookChapterEntity(bookId, 0, "Eins", 0, 1_800_000),
                BookChapterEntity(bookId, 1, "Zwei", 1_800_000, 3_600_000),
                BookChapterEntity(episodeId, 0, "Intro", 0, 60_000),
            ),
        )
        spoken.setSelected(bookId, selected = true)
        spoken.setSelected(episodeId, selected = true)

        val content = spoken.load()
        assertEquals(listOf("Eins", "Zwei"), content.books.single().chapters.map { it.title })
        assertEquals(listOf(1_800_000L), content.books.single().chapters.drop(1).map { it.startMs })
        assertEquals(listOf("Intro"), content.episodes.single().chapters.map { it.title })
    }

    @Test
    fun `books of a disabled server stay chosen but off the watch, local books go without file ids`() = runTest {
        val remote = book("b1")
        val local = book("eigen", serverId = LOCAL_BOOK_SERVER_ID)
        spoken.setSelected(remote, selected = true)
        spoken.setSelected(local, selected = true)

        db.bookDao().setServerEnabled("abs", false)

        val book = spoken.load().books.single()
        assertEquals(LOCAL_BOOK_SERVER_ID, book.serverId)
        assertNull(book.tracks.single().fileId)
        assertEquals(setOf(remote, local), spoken.selectedIds().first())
    }

    @Test
    fun `a finished book leaves the watch`() = runTest {
        val id = book("b1")
        spoken.setSelected(id, selected = true)

        db.bookDao().upsertProgress(BookProgressEntity(id, positionMs = 3_600_000, durationMs = 3_600_000, finished = true, updatedAt = 9))

        assertTrue(spoken.load().books.isEmpty())
        assertTrue(spoken.selectedIds().first().isEmpty())
    }

    private suspend fun episode(guid: String): Long {
        val id = db.bookDao().insertBook(
            BookEntity(serverId = "f1", kind = BookKind.EPISODE, remoteId = guid, title = guid, normTitle = guid, author = "Podcast", durationMs = 600_000),
        )
        db.podcastDao().insertTracks(listOf(BookTrackEntity(id, 0, "https://example.org/$guid.mp3", 0, 600_000, mimeType = "audio/mpeg")))
        return id
    }

    /** Ein Buch mit geladenen Details: [files] Dateien zu je einer Stunde. */
    private suspend fun book(remoteId: String, files: Int = 1, serverId: String = "abs"): Long {
        val id = db.bookDao().insertBook(
            BookEntity(
                serverId = serverId,
                kind = BookKind.BOOK,
                remoteId = remoteId,
                title = remoteId,
                normTitle = remoteId,
                author = "Autorin",
                durationMs = files * 3_600_000L,
                detailsLoaded = true,
            ),
        )
        db.bookDao().insertTracks(
            (0 until files).map { BookTrackEntity(id, it, if (serverId == "abs") "$remoteId-$it" else "content://buch/$it", it * 3_600_000L, 3_600_000) },
        )
        return id
    }
}
