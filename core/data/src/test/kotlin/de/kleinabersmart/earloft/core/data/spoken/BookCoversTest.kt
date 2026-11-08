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

    /** Bilder im Netz nach Adresse; alles andere gibt 404. */
    private val images = mapOf(EPISODE_IMAGE to byteArrayOf(1, 2, 3), FEED_IMAGE to byteArrayOf(7, 8, 9))

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        context.cacheDir.resolve("book_covers").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val bytes = images[chain.request().url.toString()]
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (bytes != null) 200 else 404).message("")
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
        val servers = AudiobookshelfServers(dao, secrets, api, AbsServerRegistry(), CredentialsStamps(secrets))
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
        // Auf der Uhr ist die Datei eine Kennung des Handys, keine content://-Adresse.
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
        // Das Bild ist im Netz, aber die Liste hat es nicht geladen.
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
    }
}
