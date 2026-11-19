package de.kleinabersmart.earloft.core.data.podcast

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
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
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PodcastTranscriptsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: EarLoftDatabase
    private lateinit var transcripts: PodcastTranscripts
    private var episode = 0L

    /** What the "server" is currently serving; `null` = unreachable. */
    private var served: String? = null
    private var requests = 0

    @Before
    fun setUp() {
        File(context.cacheDir, "transcripts").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests++
                val body = served ?: throw IOException("offline")
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        transcripts = PodcastTranscripts(context, db.bookDao(), PodcastFeedApi(client))
        episode = kotlinx.coroutines.runBlocking {
            db.bookDao().upsertBook(
                BookEntity(
                    serverId = "feed", kind = BookKind.EPISODE, remoteId = "guid", title = "Episode", normTitle = "episode",
                    author = "Podcast", durationMs = 600_000, transcriptUrl = "https://example.com/1.json",
                    transcriptType = "application/json",
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a broken transcript is not cached, so trying again fetches it anew`() = runTest {
        served = """{"version":"1.0.0","segments":[{"speaker":"Anna","startTime":0.5,"""
        assertEquals(TranscriptResult.Unavailable, transcripts.load(episode))

        served = GOOD
        val ready = transcripts.load(episode)
        assertTrue(ready is TranscriptResult.Ready)
        assertEquals(2, requests)

        // Now it's in the cache and available even without a connection.
        served = null
        assertEquals(ready, transcripts.load(episode))
        assertEquals(2, requests)
    }

    @Test
    fun `prefetch keeps only a readable transcript and an unreadable cached one is replaced`() = runTest {
        served = "{\"segments\":["
        transcripts.prefetch(episode)
        served = null
        assertEquals(TranscriptResult.Unavailable, transcripts.load(episode))

        // An unreadable state in the cache (from an older version) is dropped and reloaded.
        val cache = File(context.cacheDir, "transcripts")
        served = GOOD
        transcripts.prefetch(episode)
        cache.listFiles()!!.single().writeText("{\"segments\":[")
        assertTrue(transcripts.load(episode) is TranscriptResult.Ready)
        served = null
        assertTrue(transcripts.load(episode) is TranscriptResult.Ready)
    }

    private companion object {
        const val GOOD = """{"version":"1.0.0","segments":[
            {"speaker":"Anna","startTime":0.5,"endTime":0.9,"body":"Hello"},
            {"speaker":"Anna","startTime":0.9,"endTime":1.6,"body":"world."}]}"""
    }
}
