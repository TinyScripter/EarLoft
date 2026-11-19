package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.database.model.BookCoverRow
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class BookCoversTest {

    private lateinit var context: Context
    private lateinit var db: EarLoftDatabase
    private lateinit var covers: BookCovers

    /** Images on the network by address; everything else returns 404. */
    private val images = mapOf(
        EPISODE_IMAGE to byteArrayOf(1, 2, 3),
        FEED_IMAGE to byteArrayOf(7, 8, 9),
        COLLIDING_A to byteArrayOf(10),
        COLLIDING_B to byteArrayOf(11),
    )

    /** Requested addresses. */
    private val requested = mutableListOf<String>()

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.cacheDir.resolve("book_covers").deleteRecursively()
        context.noBackupFilesDir.resolve("book_covers").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requested += chain.request().url.toString()
                val bytes = images[chain.request().url.toString()]
                val code = when {
                    bytes != null -> 200
                    chain.request().url.toString() == BROKEN_IMAGE -> 500
                    else -> 404
                }
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("")
                    .body((bytes ?: ByteArray(0)).toResponseBody())
                    .build()
            }
            .build()
        val secrets = object : SecretStore {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, value: String) = Unit
            override suspend fun remove(key: String) = Unit
        }
        val dao = db.bookDao()
        val api = AbsApi(client)
        val servers = testAudiobookshelfServers(dao, secrets, api)
        covers = BookCovers(context, dao, db.podcastDao(), servers, api, PodcastFeedApi(client), LocalBookScanner(context))
        db.podcastDao().upsertFeed(PodcastFeedEntity("f1", "https://example.org/feed", "Podcast", imageUrl = FEED_IMAGE))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `episode shows its own image`() = runTest {
        val id = episode("f1", "g1", coverUrl = EPISODE_IMAGE)
        assertArrayEquals(byteArrayOf(1, 2, 3), covers.coverBytes(id))
    }

    @Test
    fun `episode without image shows the subscription image`() = runTest {
        val id = episode("f1", "g1", coverUrl = null)
        assertArrayEquals(byteArrayOf(7, 8, 9), covers.coverBytes(id))
    }

    @Test
    fun `episode whose image is gone shows the subscription image`() = runTest {
        val id = episode("f1", "g1", coverUrl = "https://example.org/weg.jpg")
        assertArrayEquals(byteArrayOf(7, 8, 9), covers.coverBytes(id))
    }

    @Test
    fun `episode of a local podcast falls back to the cover of another episode`() = runTest {
        val feedId = "local:eigen"
        val withCover = episode(feedId, "a.mp3", coverUrl = null)
        val without = episode(feedId, "b.mp3", coverUrl = null)
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId, feedId, "Eigen", imageUrl = AppUris.bookCover(withCover)))
        covers.store(withCover, byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), covers.coverBytes(without))
    }

    @Test
    fun `book from the phone storage has no cover on the watch until it is stored`() = runTest {
        // On the watch the file is a phone identifier, not a content:// address.
        val id = db.bookDao().upsertBook(book(LOCAL_BOOK_SERVER_ID, "Buch"))
        db.bookDao().insertTracks(listOf(BookTrackEntity(id, 0, "book:local:Buch:0", 0, 1_000)))
        assertNull(covers.coverBytes(id))
        covers.store(id, byteArrayOf(6))
        assertArrayEquals(byteArrayOf(6), covers.coverBytes(id))
    }

    @Test
    fun `thumbnail only comes from the cache`() = runTest {
        val id = episode("f1", "g1", coverUrl = EPISODE_IMAGE)
        assertNull(covers.thumbnail(id, 96))
        // The image is on the network, but the list didn't download it.
        assertArrayEquals(byteArrayOf(1, 2, 3), covers.coverBytes(id))
    }

    @Test
    fun `thumbnail is scaled down`() = runTest {
        val id = db.bookDao().upsertBook(book("abs", "item"))
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        covers.store(id, png)
        val thumbnail = covers.thumbnail(id, 96)!!
        assertEquals(96, thumbnail.width)
        assertEquals(72, thumbnail.height)
    }

    @Test
    fun `images are kept outside the cache directory`() = runTest {
        val id = episode("f1", "g1", coverUrl = EPISODE_IMAGE)
        covers.coverBytes(id)
        context.cacheDir.deleteRecursively()
        assertArrayEquals(byteArrayOf(1, 2, 3), covers.coverBytes(id))
    }

    @Test
    fun `subscription image is stored under its address`() = runTest {
        assertNull(covers.storedImage(FEED_IMAGE))
        assertTrue(covers.ensureFeedImageCached("f1"))
        assertArrayEquals(byteArrayOf(7, 8, 9), covers.storedImage(FEED_IMAGE)!!.readBytes())
    }

    @Test
    fun `own image tells a missing image from an unreachable server`() = runTest {
        val gone = episode("f1", "g1", coverUrl = "https://example.org/weg.jpg")
        val broken = episode("f1", "g2", coverUrl = BROKEN_IMAGE)
        val fine = episode("f1", "g3", coverUrl = EPISODE_IMAGE)
        assertTrue(covers.ensureOwnCached(gone))
        assertFalse(covers.ensureOwnCached(broken))
        assertTrue(covers.ensureOwnCached(fine))
        assertArrayEquals(byteArrayOf(1, 2, 3), covers.storedImage(EPISODE_IMAGE)!!.readBytes())
    }

    @Test
    fun `episodes sharing an image that is already there are not loaded again`() = runTest {
        val rows = listOf("g1", "g2", "g3").map { BookCoverRow(episode("f1", it, coverUrl = EPISODE_IMAGE), "f1", BookKind.EPISODE, EPISODE_IMAGE) }

        assertTrue(rows.all { covers.ensureOwnCached(it) })

        assertEquals(listOf(EPISODE_IMAGE), requested)
        assertArrayEquals(byteArrayOf(1, 2, 3), covers.storedImage(EPISODE_IMAGE)!!.readBytes())
    }

    @Test
    fun `large images are stored scaled down`() = runTest {
        val id = db.bookDao().upsertBook(book("abs", "item"))
        val jpeg = ByteArrayOutputStream().also {
            Bitmap.createBitmap(3000, 1500, Bitmap.Config.RGB_565).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        covers.store(id, jpeg)
        val stored = covers.cover(id)!!
        assertEquals(800, stored.width)
        assertEquals(400, stored.height)
    }

    @Test
    fun `images without book or subscription are removed`() = runTest {
        val kept = episode("f1", "g1", coverUrl = EPISODE_IMAGE)
        val removed = episode("f2", "g2", coverUrl = null)
        covers.coverBytes(kept)
        covers.ensureFeedImageCached("f1")
        covers.store(removed, byteArrayOf(5))
        db.bookDao().deleteBooksOf("f2")
        assertEquals(1, covers.retainKnown())
        assertArrayEquals(byteArrayOf(1, 2, 3), covers.coverBytes(kept))
        assertArrayEquals(byteArrayOf(7, 8, 9), covers.storedImage(FEED_IMAGE)!!.readBytes())
    }

    @Test
    fun `addresses with the same hash code keep their own images`() = runTest {
        assertEquals(COLLIDING_A.hashCode(), COLLIDING_B.hashCode())
        val a = episode("f1", "g1", coverUrl = COLLIDING_A)
        val b = episode("f1", "g2", coverUrl = COLLIDING_B)

        assertArrayEquals(byteArrayOf(10), covers.coverBytes(a))
        assertArrayEquals(byteArrayOf(11), covers.coverBytes(b))
    }

    @Test
    fun `an image stored under the old name is taken over, not loaded again`() = runTest {
        val id = episode("f1", "g1", coverUrl = EPISODE_IMAGE)
        val legacy = context.noBackupFilesDir.resolve("book_covers/u${EPISODE_IMAGE.hashCode()}.img")
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(byteArrayOf(4, 2))

        assertEquals(0, covers.retainKnown())

        assertFalse(legacy.exists())
        assertArrayEquals(byteArrayOf(4, 2), covers.coverBytes(id))
        assertTrue(requested.isEmpty())
    }

    private suspend fun episode(feedId: String, guid: String, coverUrl: String?): Long =
        db.bookDao().upsertBook(book(feedId, guid).copy(kind = BookKind.EPISODE, coverUrl = coverUrl))

    private fun book(serverId: String, remoteId: String) = BookEntity(
        serverId = serverId,
        kind = BookKind.BOOK,
        remoteId = remoteId,
        title = remoteId,
        normTitle = remoteId,
        author = "Autor",
        durationMs = 1_000,
        detailsLoaded = true,
    )

    private companion object {
        const val EPISODE_IMAGE = "https://example.org/folge.jpg"
        const val FEED_IMAGE = "https://example.org/abo.jpg"
        const val BROKEN_IMAGE = "https://example.org/kaputt.jpg"

        // "Aa" and "BB" have the same `hashCode`.
        const val COLLIDING_A = "https://example.org/Aa.jpg"
        const val COLLIDING_B = "https://example.org/BB.jpg"
    }
}
