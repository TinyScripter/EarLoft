package de.kleinabersmart.earloft.core.data.podcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvent
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvents
import de.kleinabersmart.earloft.core.data.refresh.RefreshKind
import de.kleinabersmart.earloft.core.data.spoken.LocalPodcasts
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.sync.SweepGuard
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.datasource.local.AudioFileChapters
import de.kleinabersmart.earloft.core.datasource.local.LocalScan
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdSoundsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.IOException
import java.util.Collections

/**
 * When subscriptions are synced: on opening the app only the stale ones and only over an allowed network, on request
 * everything, periodically by the job. Real repository and database; the feeds come from an interceptor.
 */
@RunWith(RobolectricTestRunner::class)
class PodcastRefresherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: EarLoftDatabase
    private lateinit var repository: PodcastRepository
    private lateinit var workManager: WorkManager
    private val settings = PodcastSettings(context)
    private val spokenSettings = SpokenSettings(context)
    private val events = RefreshEvents()

    /** Feed per address; missing = unreachable. */
    private val served = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val requested = Collections.synchronizedList(mutableListOf<String>())
    private var localScans = 0

    /** The refresher's application scope; [settle] waits for what it launched. */
    private val work = Job()
    private val scope = CoroutineScope(Dispatchers.Default + work)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requested += url
                val body = served[url] ?: throw IOException("offline")
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/rss+xml".toMediaType()))
                    .build()
            }
            .build()
        repository = PodcastRepository(
            db, db.podcastDao(), db.bookDao(), PodcastFeedApi(client), ArdSoundsApi(client), settings,
            AudioFileChapters(context), { error("no source switch in tests") }, testFeedCredentials(db), EpisodeQueue(context, db.bookDao()),
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        network(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @After
    fun tearDown() = runBlocking {
        scope.cancel()
        // The DataStores outlive the test.
        settings.setRefreshHours(PodcastSettings.DEFAULT_REFRESH_HOURS)
        settings.setUseMobileData(false)
        spokenSettings.setAreaEnabled(SpokenMode.PODCASTS, true)
        spokenSettings.setUseLocalPodcasts(false)
        db.close()
    }

    private fun refresher(awaitRestore: suspend () -> Unit = {}) =
        PodcastRefresher(context, repository, db.podcastDao(), settings, localPodcasts(), events, scope, awaitRestore)

    /** Podcasts on the device only count how often they were looked for. */
    private fun localPodcasts() = LocalPodcasts(
        db.bookDao(), db.podcastDao(), { localScans++; LocalScan(emptyList()) }, spokenSettings, {}, SweepGuard(context), { false },
    )

    private fun network(vararg capabilities: Int) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = ShadowNetworkCapabilities.newInstance().also { nc -> capabilities.forEach { shadowOf(nc).addCapability(it) } }
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, network)
    }

    /** Waits until everything the refresher launched is done. */
    private suspend fun settle() = withTimeout(10_000) { work.children.toList().joinAll() }

    /** Records the refresh events while [block] runs. */
    private suspend fun recorded(block: suspend () -> Unit): List<RefreshEvent> {
        val got = Collections.synchronizedList(mutableListOf<RefreshEvent>())
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { events.events.collect { got += it } }
        try {
            block()
        } finally {
            collector.cancel()
        }
        return got.toList()
    }

    /** Subscribes to [name] and marks its last sync [hoursAgo]. */
    private suspend fun subscribed(name: String, hoursAgo: Int): String {
        served[url(name)] = feed(name, "1")
        val feedId = (repository.subscribe(url(name)) as SubscribeResult.Added).feedId
        val feed = db.podcastDao().feed(feedId)!!
        db.podcastDao().upsertFeed(feed.copy(lastRefreshAt = System.currentTimeMillis() - hoursAgo * HOUR_MS))
        // A new episode is out since then.
        served[url(name)] = feed(name, "1", "2")
        requested.clear()
        return feedId
    }

    @Test
    fun `opening the app syncs only stale subscriptions and reports what is new`() = runBlocking {
        subscribed("fresh", hoursAgo = 1)
        subscribed("stale", hoursAgo = 7)
        val refresher = refresher()

        val got = recorded {
            refresher.refreshStale()
            settle()
        }

        assertEquals(listOf(url("stale")), requested)
        assertEquals(listOf(RefreshEvent.Finished(RefreshKind.PODCASTS, 1, failed = false)), got)

        // Opened again right away: no second look.
        refresher.refreshStale()
        settle()
        assertEquals(1, requested.size)
    }

    @Test
    fun `opening the app during the first restore also syncs the subscriptions it brings back`() = runBlocking {
        val restore = CompletableDeferred<Unit>()
        val refresher = refresher { restore.await() }

        refresher.refreshStale()
        // The restore creates the backed-up subscription only now, never synced.
        subscribed("restored", hoursAgo = 0)
        db.podcastDao().upsertFeed(db.podcastDao().feeds().single().copy(lastRefreshAt = 0))
        restore.complete(Unit)
        settle()

        assertEquals(listOf(url("restored")), requested)
    }

    @Test
    fun `nothing new on opening the app reports nothing`() = runBlocking {
        subscribed("stale", hoursAgo = 7)
        served[url("stale")] = feed("stale", "1")

        val got = recorded {
            refresher().refreshStale()
            settle()
        }

        assertEquals(1, requested.size)
        assertTrue(got.isEmpty())
    }

    @Test
    fun `opening the app syncs nothing over mobile data, behind a login page or when switched to manual`() = runBlocking {
        subscribed("stale", hoursAgo = 7)

        network(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        refresher().refreshStale()
        settle()
        assertTrue(requested.isEmpty())

        network(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        refresher().refreshStale()
        settle()
        assertTrue(requested.isEmpty())

        network(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED, NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        settings.setRefreshHours(0)
        refresher().refreshStale()
        settle()
        assertTrue(requested.isEmpty())

        // Mobile data allowed: then it may.
        settings.setRefreshHours(6)
        settings.setUseMobileData(true)
        network(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        refresher().refreshStale()
        settle()
        assertEquals(listOf(url("stale")), requested)
    }

    @Test
    fun `syncing on request covers every subscription and the device folder`() = runBlocking {
        subscribed("fresh", hoursAgo = 1)
        subscribed("stale", hoursAgo = 7)
        spokenSettings.setUseLocalPodcasts(true)
        val refresher = refresher()

        val got = recorded {
            refresher.refreshAll()
            settle()
        }

        assertEquals(setOf(url("fresh"), url("stale")), requested.toSet())
        assertEquals(1, localScans)
        assertEquals(
            listOf(RefreshEvent.Started(RefreshKind.PODCASTS), RefreshEvent.Finished(RefreshKind.PODCASTS, 2, failed = false)),
            got,
        )
        assertFalse(refresher.refreshing.value)
    }

    @Test
    fun `a failed sync on request is reported as failed`() = runBlocking {
        val feedId = subscribed("gone", hoursAgo = 7)
        served.clear()
        val refresher = refresher()

        val all = recorded {
            refresher.refreshAll()
            settle()
        }
        val one = recorded {
            refresher.refreshFeed(feedId)
            settle()
        }

        assertEquals(RefreshEvent.Finished(RefreshKind.PODCASTS, 0, failed = true), all.last())
        assertEquals(RefreshEvent.Finished(RefreshKind.PODCASTS, 0, failed = true), one.last())
        assertFalse(refresher.refreshing.value)
    }

    @Test
    fun `the periodic job follows interval and network setting`() = runBlocking {
        settings.setRefreshHours(12)
        refresher().start()

        val wifi = awaitJob { it.constraints.requiredNetworkType == NetworkType.UNMETERED }
        assertEquals(12 * HOUR_MS, wifi.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(wifi.constraints.requiresBatteryNotLow())

        settings.setUseMobileData(true)
        awaitJob { it.constraints.requiredNetworkType == NetworkType.CONNECTED }

        settings.setRefreshHours(0)
        withTimeout(10_000) {
            while (jobs().any { !it.state.isFinished }) delay(20)
        }
    }

    private fun jobs(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork("podcast-refresh").get()

    private suspend fun awaitJob(matches: (WorkInfo) -> Boolean): WorkInfo = withTimeout(10_000) {
        var job: WorkInfo? = null
        while (job == null) {
            job = jobs().firstOrNull { !it.state.isFinished && matches(it) }
            if (job == null) delay(20)
        }
        job
    }

    // --- The periodic job ---

    private val shown = mutableListOf<NewContent>()

    private val entryPoint = object : PodcastRefreshWorker.RefreshEntryPoint {
        override fun repository() = repository
        override fun notifier() = object : NewContentNotifier {
            override suspend fun show(content: NewContent) {
                shown += content
            }
        }
        override fun spokenSettings() = spokenSettings
    }

    private suspend fun runJob(attempt: Int = 0): ListenableWorker.Result =
        TestListenableWorkerBuilder<PodcastRefreshWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    PodcastRefreshWorker(appContext, workerParameters, entryPoint)
            })
            .build()
            .doWork()

    @Test
    fun `the job notifies only about podcasts that want it`() = runBlocking {
        subscribed("loud", hoursAgo = 7)
        val quiet = subscribed("quiet", hoursAgo = 7)
        db.podcastDao().upsertFeed(db.podcastDao().feed(quiet)!!.copy(notifyNew = false))

        assertEquals(ListenableWorker.Result.success(), runJob())

        val content = shown.single() as NewContent.Podcasts
        assertEquals(listOf("loud"), content.episodes.map { it.feedTitle })
    }

    @Test
    fun `the job retries a few times only if no feed was reachable`() = runBlocking {
        subscribed("a", hoursAgo = 7)
        subscribed("b", hoursAgo = 7)

        // One dead feed: no retry, the other one's news is still announced.
        served.remove(url("a"))
        assertEquals(ListenableWorker.Result.success(), runJob())
        assertEquals(listOf("b"), (shown.single() as NewContent.Podcasts).episodes.map { it.feedTitle })
        shown.clear()

        served.clear()
        assertEquals(ListenableWorker.Result.retry(), runJob(attempt = 0))
        assertEquals(ListenableWorker.Result.retry(), runJob(attempt = 2))
        assertEquals(ListenableWorker.Result.success(), runJob(attempt = 3))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `the job fetches nothing while podcasts are hidden`() = runBlocking {
        subscribed("a", hoursAgo = 7)
        spokenSettings.setAreaEnabled(SpokenMode.PODCASTS, false)

        assertEquals(ListenableWorker.Result.success(), runJob())

        assertTrue(requested.isEmpty())
        assertTrue(shown.isEmpty())
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L

        fun url(name: String) = "https://example.com/$name.xml"

        private val DAYS = mapOf("1" to "Sat, 01", "2" to "Sun, 02")

        /** Episode `n` is from day `n` of November 2025, so higher numbers are newer. */
        fun feed(name: String, vararg episodes: String): String = buildString {
            append("""<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>$name</title>""")
            episodes.forEach { n ->
                append("<item><title>$name $n</title><guid>$name-$n</guid><pubDate>${DAYS.getValue(n)} Nov 2025 06:00:00 GMT</pubDate>")
                append("""<enclosure url="https://example.com/$name-$n.mp3" type="audio/mpeg"/><itunes:duration>10:00</itunes:duration></item>""")
            }
            append("</channel></rss>")
        }
    }
}
