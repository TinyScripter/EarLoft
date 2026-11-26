package de.kleinabersmart.earloft.core.data.podcast

import android.content.Context
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import de.kleinabersmart.earloft.core.data.backup.MusicBackup
import de.kleinabersmart.earloft.core.data.backup.RestoreNotice
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.BookKeyRenamer
import de.kleinabersmart.earloft.core.data.spoken.BookKeys
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.UserDatabase
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.ParsedEpisode
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PodcastSourceSwitchTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: EarLoftDatabase
    private lateinit var user: UserDatabase
    private lateinit var repository: PodcastRepository
    private lateinit var switch: PodcastSourceSwitch
    private val renamed = mutableListOf<Map<String, String>>()

    /** Whether a transaction of the main database was open while the renamer ran (review4 T1). */
    private val renamedInTransaction = mutableListOf<Boolean>()

    /** Custom responses per address; otherwise every host serves [RSS]. */
    private val bodies = mutableMapOf<String, String>()
    private val requests = mutableListOf<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        user = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request().url.toString()
                val ard = chain.request().url.host == "api.ardaudiothek.de"
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (ard) SHOW else bodies[chain.request().url.toString()] ?: RSS).toResponseBody("application/rss+xml".toMediaType()))
                    .build()
            }
            .build()
        repository = PodcastRepository(db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client), PodcastSettings(ApplicationProvider.getApplicationContext()), AudioFileChapters(ApplicationProvider.getApplicationContext()), { switch }, testFeedCredentials(db), EpisodeQueue(ApplicationProvider.getApplicationContext(), db.bookDao()))
        val backup = UserDataBackup(
            context = context,
            database = db,
            userDao = user.userDataDao(),
            sourceDao = db.sourceDao(),
            bookDao = db.bookDao(),
            podcastDao = db.podcastDao(),
            statsDao = db.statsDao(),
            music = MusicBackup(db.backupDao(), db.playlistDao(), db.syncDao()),
            restoreNotice = RestoreNotice(context, { error("not needed") }, { error("not needed") }),
            registry = Lazy<DataSourceRegistry> { error("not needed") },
            ardBooks = { error("not needed") },
            scope = TestScope(),
        )
        val renamer = object : BookKeyRenamer {
            override suspend fun renameBookKeys(renames: Map<String, String>) {
                renamed += renames
                renamedInTransaction += db.inTransaction()
            }
        }
        switch = PodcastSourceSwitch(db, repository, db.podcastDao(), db.bookDao(), db.statsDao(), backup, setOf(renamer))
    }

    @After
    fun tearDown() {
        db.close()
        user.close()
    }

    private suspend fun idOf(feedId: String, title: String) = db.bookDao().booksOf(feedId).single { it.title == title }.bookId

    @Test
    fun `switching to ARD Sounds keeps episodes, progress, downloads and statistics under the new keys`() = runTest {
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        val a = idOf(feedId, "Episode A")
        val b = idOf(feedId, "Episode B")
        db.bookDao().upsertProgress(BookProgressEntity(b, 60_000, 600_000, false, 1f, 100))
        db.openHelper.writableDatabase.execSQL("UPDATE book_tracks SET downloadState = 'DONE', filePath = '/a.mp3' WHERE bookId = $a")
        db.statsDao().addListening(
            ListeningStatEntity(source = "app", kind = BookKind.EPISODE, groupKey = OLD_URL, groupTitle = "Talk", itemKey = "guid-B",
                itemTitle = "Episode B", listenedMs = 60_000, lastListenedAt = 100),
            day = 20_000,
        )
        user.openHelper.writableDatabase.execSQL(
            "INSERT INTO book_progress (serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty) " +
                "VALUES ('$feedId', 'guid-B', 60000, 600000, 0, 1.0, 100, 0)",
        )

        val check = switch.check(feedId, "ardsounds:urn:ard:show:abc") as SourceCheck.Ready
        assertEquals(3, check.episodes)
        assertEquals(2, check.matched)
        assertEquals(0, check.keptUnmatched)

        assertTrue(switch.switch(check))

        val feed = db.podcastDao().feed(feedId)!!
        assertEquals("ardsounds:urn:ard:show:abc", feed.url)
        // A via the audio address (without query), B via title and date; C is missing in ARD Sounds and wasn't started.
        assertEquals("urn:ard:publication:a", db.bookDao().book(a)!!.remoteId)
        assertEquals("urn:ard:publication:b", db.bookDao().book(b)!!.remoteId)
        assertEquals(listOf("Episode A (Rerun)", "Episode B", "Episode D"), db.bookDao().booksOf(feedId).map { it.title }.sorted())
        assertEquals(60_000L, db.bookDao().progress(b)!!.positionMs)
        val track = db.bookDao().tracks(a).single()
        assertEquals("https://cdn.example/a.mp3", track.remoteFileId)
        assertEquals(TrackDownload.DONE, track.downloadState)
        val stat = db.statsDao().all().single()
        assertEquals("ardsounds:urn:ard:show:abc" to "urn:ard:publication:b", stat.groupKey to stat.itemKey)
        assertEquals(60_000L, stat.listenedMs)
        assertEquals(listOf("ardsounds:urn:ard:show:abc"), db.statsDao().allDays().map { it.groupKey })
        assertEquals(
            listOf(
                mapOf(
                    BookKeys.encode(feedId, "guid-A") to BookKeys.encode(feedId, "urn:ard:publication:a"),
                    BookKeys.encode(feedId, "guid-B") to BookKeys.encode(feedId, "urn:ard:publication:b"),
                ),
            ),
            renamed,
        )
        assertTrue(user.userDataDao().progress().none { it.remoteId == "guid-B" })
        // Syncing again leaves everything as it is.
        repository.refresh(feedId)
        assertEquals(b, idOf(feedId, "Episode B"))
    }

    @Test
    fun `a feed announcing a new address moves there with its statistics`() = runTest {
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        val b = idOf(feedId, "Episode B")
        db.statsDao().addListening(
            ListeningStatEntity(source = "app", kind = BookKind.EPISODE, groupKey = OLD_URL, groupTitle = "Talk", itemKey = "guid-B",
                itemTitle = "Episode B", listenedMs = 60_000, lastListenedAt = 100),
            day = 20_000,
        )
        bodies[OLD_URL] = RSS.replace("<title>Talk without guest</title>", "<title>Talk without guest</title>$NEW_FEED_URL")

        assertEquals(true, repository.refreshAll().moved == listOf("Talk without guest"))

        assertEquals(NEW_URL, db.podcastDao().feed(feedId)!!.url)
        assertEquals(b, idOf(feedId, "Episode B"))
        assertEquals(NEW_URL to "guid-B", db.statsDao().all().single().let { it.groupKey to it.itemKey })
        assertEquals(emptyList<Map<String, String>>(), renamed)
    }

    @Test
    fun `a move is refused when the new address points back or is subscribed`() = runTest {
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        bodies[OLD_URL] = RSS.replace("<title>Talk without guest</title>", "<title>Talk without guest</title>$NEW_FEED_URL")
        bodies[NEW_URL] = RSS.replace("<title>Talk without guest</title>", "<title>Talk without guest</title>$OLD_FEED_URL")

        repository.refresh(feedId)
        assertEquals(OLD_URL, db.podcastDao().feed(feedId)!!.url)

        val taken = "https://taken.example/feed.xml"
        val other = (repository.subscribe("https://other.example/feed.xml") as SubscribeResult.Added).feedId
        db.openHelper.writableDatabase.execSQL("UPDATE podcast_feeds SET url = '$taken' WHERE feedId = '$other'")
        assertEquals(false, switch.follow(feedId, taken))
        assertEquals(OLD_URL, db.podcastDao().feed(feedId)!!.url)
    }

    @Test
    fun `a move is refused when the new address points back in another spelling`() = runTest {
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        val spelledBack = OLD_FEED_URL.replace("https://bridge.example", "https://BRIDGE.example:443")
        bodies[OLD_URL] = RSS.replace("<title>Talk without guest</title>", "<title>Talk without guest</title>$NEW_FEED_URL")
        bodies[NEW_URL] = RSS.replace("<title>Talk without guest</title>", "<title>Talk without guest</title>$spelledBack")

        // Otherwise the subscription moves and moves back on the next process, each time with new identifiers and statistics.
        assertEquals(false, switch.follow(feedId, NEW_URL))
        assertEquals(OLD_URL, db.podcastDao().feed(feedId)!!.url)
    }

    @Test
    fun `an announced address that only spells the own address differently is not even fetched`() = runTest {
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        val spelled = OLD_FEED_URL.replace("https://bridge.example", "https://BRIDGE.example:443")
        bodies[OLD_URL] = RSS.replace("<title>Talk without guest</title>", "<title>Talk without guest</title>$spelled")
        requests.clear()

        repository.refresh(feedId)

        assertEquals(listOf(OLD_URL), requests)
        assertEquals(OLD_URL, db.podcastDao().feed(feedId)!!.url)
    }

    @Test
    fun `the same source or one already subscribed is refused`() = runTest {
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        assertEquals(SourceCheck.SameSource, switch.check(feedId, OLD_URL))
        repository.subscribe("ardsounds:urn:ard:show:abc")
        assertEquals(SourceCheck.Taken("Talk without guest"), switch.check(feedId, "ardsounds:urn:ard:show:abc"))
    }

    @Test
    fun `a switch checked before the subscription changed does nothing`() = runTest {
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        val check = switch.check(feedId, "ardsounds:urn:ard:show:abc") as SourceCheck.Ready
        repository.subscribe("ardsounds:urn:ard:show:abc")

        assertEquals(false, switch.switch(check))
        assertEquals(OLD_URL, db.podcastDao().feed(feedId)!!.url)
    }

    @Test
    fun `episodes match by guid, then audio address, then title near the same date`() {
        val day = 24 * 60 * 60 * 1000L
        val known = listOf(
            KnownEpisode(1, "same", "https://a.example/1.mp3", "One", 10 * day),
            KnownEpisode(2, "old-2", "http://CDN.example/2.mp3?ref=feed", "Two", 10 * day),
            KnownEpisode(3, "old-3", "https://a.example/3.mp3", "Three!", 10 * day),
            KnownEpisode(4, "old-4", "https://a.example/4.mp3", "Four", 10 * day),
        )
        val fresh = listOf(
            episode("same", "https://b.example/1.mp3", "Different", 0),
            episode("new-2", "https://cdn.example/2.mp3", "Two (new)", 30 * day),
            episode("new-3", "https://b.example/3.mp3", "three", 11 * day),
            episode("new-4", "https://b.example/4.mp3", "Four", 20 * day),
        )

        val matches = matchEpisodes(known, fresh).mapValues { it.value.guid }

        assertEquals(mapOf(1L to "same", 2L to "new-2", 3L to "new-3"), matches)
    }

    @Test
    fun `statistics move to the new group and add up where entries meet`() {
        fun stat(source: String, group: String, item: String, ms: Long, at: Long) =
            ListeningStatEntity(statId = 7, source = source, kind = BookKind.EPISODE, groupKey = group, groupTitle = "T",
                itemKey = item, itemTitle = item, listenedMs = ms, lastListenedAt = at)
        val rows = listOf(
            stat("app", "old", "g1", 100, 5),
            stat("app", "new", "n1", 50, 9),
            stat("imported", "old", "g1", 30, 0),
            stat("app", "old", "g2", 10, 1),
        )

        val rekeyed = rekeyStats(rows, "old", "new", mapOf("g1" to "n1"))
            .associate { (it.source to it.itemKey) to (it.groupKey to it.listenedMs) }

        assertEquals(
            mapOf(("app" to "n1") to ("new" to 150L), ("imported" to "n1") to ("new" to 30L), ("app" to "g2") to ("new" to 10L)),
            rekeyed,
        )
        assertEquals(
            listOf(ListeningDayEntity(1, BookKind.EPISODE, "new", 7, savedMs = 5)),
            rekeyDays(
                listOf(ListeningDayEntity(1, BookKind.EPISODE, "old", 4, savedMs = 2), ListeningDayEntity(1, BookKind.EPISODE, "new", 3, savedMs = 3)),
                "old",
                "new",
            ),
        )
    }

    private fun episode(guid: String, audio: String, title: String, publishedAt: Long) =
        ParsedEpisode(guid, title, "", audio, null, 0, publishedAt, null, null)

    @Test
    fun `episodes without guid keep progress and keys when the audio address gets a new token`() = runTest {
        fun noGuid(token: String) = """<rss version="2.0"><channel><title>Talk</title>
            <item><title>Episode A</title><pubDate>Thu, 30 Oct 2025 06:00:00 GMT</pubDate>
            <enclosure url="https://cdn.example/a.mp3?token=$token" type="audio/mpeg"/></item>
            <item><title>Episode B</title><pubDate>Wed, 29 Oct 2025 06:00:00 GMT</pubDate>
            <enclosure url="https://cdn.example/b.mp3?token=$token" type="audio/mpeg"/></item>
            </channel></rss>"""
        bodies[OLD_URL] = noGuid("1")
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        val b = idOf(feedId, "Episode B")
        db.bookDao().upsertProgress(BookProgressEntity(b, 60_000, 600_000, false, 1f, 100))

        bodies[OLD_URL] = noGuid("2")
        val result = repository.refresh(feedId)!!

        assertTrue("nothing counts as new", result.newEpisodeIds.isEmpty())
        val books = db.bookDao().booksOf(feedId)
        assertEquals(2, books.size)
        assertEquals("https://cdn.example/b.mp3?token=2", books.single { it.bookId == b }.remoteId)
        assertEquals(60_000L, db.bookDao().progress(b)!!.positionMs)
        assertEquals(
            BookKeys.encode(feedId, "https://cdn.example/b.mp3?token=2"),
            renamed.single()[BookKeys.encode(feedId, "https://cdn.example/b.mp3?token=1")],
        )
    }

    @Test
    fun `episodes without guid renamed by a switch are followed up after the commit, not inside the transaction`() = runTest {
        fun noGuid(host: String, date: String, suffix: String) = """<rss version="2.0"><channel><title>Talk</title>
            <item><title>Episode B</title><pubDate>$date</pubDate>
            <enclosure url="https://$host/b.mp3$suffix" type="audio/mpeg"/></item>
            </channel></rss>"""
        bodies[OLD_URL] = noGuid("cdn.example", "Wed, 29 Oct 2025 06:00:00 GMT", "?token=1")
        val feedId = (repository.subscribe(OLD_URL) as SubscribeResult.Added).feedId
        val b = idOf(feedId, "Episode B")
        // Another host and more than two days apart: the switch does not match it, only the sync recognizes it by title and file name.
        bodies[NEW_URL] = noGuid("other.example", "Fri, 10 Oct 2025 06:00:00 GMT", "")

        val check = switch.check(feedId, NEW_URL) as SourceCheck.Ready
        assertEquals(0, check.matched)
        assertTrue(switch.switch(check))

        // The one episode stays the same one with its new ID; the renamer ran after the commit, where it may take the lock of the backup.
        assertEquals("https://other.example/b.mp3", db.bookDao().book(b)!!.remoteId)
        assertEquals(1, db.bookDao().booksOf(feedId).size)
        assertEquals(
            mapOf(BookKeys.encode(feedId, "https://cdn.example/b.mp3?token=1") to BookKeys.encode(feedId, "https://other.example/b.mp3")),
            renamed.single(),
        )
        assertEquals(listOf(false), renamedInTransaction)
    }

    private companion object {
        const val OLD_URL = "https://bridge.example/feed.xml"
        const val NEW_URL = "https://new.example/feed.xml"
        const val NEW_FEED_URL = "<itunes:new-feed-url xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">$NEW_URL</itunes:new-feed-url>"
        const val OLD_FEED_URL = "<itunes:new-feed-url xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">$OLD_URL</itunes:new-feed-url>"

        val RSS = """
            <rss version="2.0"><channel><title>Talk without guest</title>
            <item><title>Episode A</title><guid>guid-A</guid><pubDate>Tue, 20 Oct 2026 06:00:00 GMT</pubDate>
              <enclosure url="https://cdn.example/a.mp3?source=bridge" type="audio/mpeg"/></item>
            <item><title>Episode B</title><guid>guid-B</guid><pubDate>Tue, 13 Oct 2026 06:00:00 GMT</pubDate>
              <enclosure url="https://bridge.example/b.mp3" type="audio/mpeg"/></item>
            <item><title>Episode C</title><guid>guid-C</guid><pubDate>Tue, 06 Oct 2026 06:00:00 GMT</pubDate>
              <enclosure url="https://bridge.example/c.mp3" type="audio/mpeg"/></item>
            </channel></rss>
        """.trimIndent()

        val SHOW = """
            {"data":{"show":{"id":"9","coreId":"urn:ard:show:abc","title":"Talk without guest","publicationService":{"title":"ARD"},
            "items":{"nodes":[
              {"coreId":"urn:ard:publication:d","title":"Episode D","publishDate":"2026-10-27T06:00:00+01:00","duration":600,
               "audios":[{"url":"https://cdn.example/d.mp3","downloadUrl":"https://cdn.example/d.mp3","allowDownload":true,"mimeType":"audio/mp3"}]},
              {"coreId":"urn:ard:publication:a","title":"Episode A (Rerun)","publishDate":"2026-10-20T08:00:00+02:00","duration":600,
               "audios":[{"url":"https://cdn.example/a.mp3","downloadUrl":"https://cdn.example/a.mp3","allowDownload":true,"mimeType":"audio/mp3"}]},
              {"coreId":"urn:ard:publication:b","title":"Episode B","publishDate":"2026-10-13T20:00:00+02:00","duration":600,
               "audios":[{"url":"https://cdn.example/b.mp3","downloadUrl":"https://cdn.example/b.mp3","allowDownload":true,"mimeType":"audio/mp3"}]}
            ]}}}}
        """.trimIndent()
    }
}
