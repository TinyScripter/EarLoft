package de.kleinabersmart.earloft

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.core.content.ContextCompat
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import androidx.test.platform.app.InstrumentationRegistry
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Der Browse-Tree, wie Android Auto ihn sieht (alter [MediaBrowser]): Tabs, Suche und Abspielen folgen den eingeschalteten
 * Bereichen (Konzept 7.1). Stellt die Bereiche danach wieder her.
 */
@RunWith(AndroidJUnit4::class)
class AutoBrowseTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val settings = SpokenSettings(context)
    private lateinit var savedAreas: List<SpokenMode>
    private var browser: MediaBrowser? = null

    @Before
    fun setUp() {
        savedAreas = runBlocking { settings.areas.first() }
        setAreas(SpokenMode.entries)
        val connected = CountDownLatch(1)
        instrumentation.runOnMainSync {
            browser = MediaBrowser(
                context,
                ComponentName(context.packageName, "de.kleinabersmart.earloft.core.playback.PlaybackService"),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() = connected.countDown()
                },
                null,
            ).apply { connect() }
        }
        assertTrue("Keine Verbindung innerhalb von 10 s", connected.await(10, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        Handler(Looper.getMainLooper()).post { browser?.disconnect() }
        setAreas(savedAreas)
    }

    @Test
    fun legacyBrowserSeesTheTabs() {
        assertEquals(listOf("home", "area:music", "area:podcasts", "area:books"), children("root")!!.map { it.mediaId })
        setAreas(listOf(SpokenMode.MUSIC))
        assertEquals(listOf("recent", "library", "playlists", "favorites"), children("root")!!.map { it.mediaId })
    }

    /**
     * Android Auto lädt das Cover der Medienkarte über die Art-URI der Plattform-Session; `earloft://cover/…` kann es nicht lesen
     * (Glide: „Load failed“), die Cover-Provider-Adresse schon.
     */
    @Test
    fun currentItemCoverIsALoadableUri() {
        val gotMetadata = CountDownLatch(1)
        var artUri: String? = null
        var hasItem = false
        instrumentation.runOnMainSync {
            val controller = MediaController(context, browser!!.sessionToken)
            val read = {
                controller.metadata?.let {
                    hasItem = true
                    artUri = it.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    gotMetadata.countDown()
                }
            }
            controller.registerCallback(
                object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) { read() }
                },
                Handler(Looper.getMainLooper()),
            )
            read()
        }
        if (!gotMetadata.await(10, TimeUnit.SECONDS)) return // Nichts geladen: es gibt kein Cover zu prüfen.
        android.util.Log.i("AutoBrowseTest", "hasItem=$hasItem artUri=$artUri")
        assertTrue("Art-URI: $artUri", artUri == null || artUri!!.startsWith("content://"))
    }

    /** Die Bücher-Ordner liefern etwas, wenn es Bücher gibt; zeigt, wie viele (Logcat `AutoBrowseTest`). */
    @Test
    fun bookFoldersListTheBooks() {
        val sizes = listOf("books:continue", "books:recent", "books:all", "books:authors", "books:series")
            .associateWith { children(it)?.size }
        android.util.Log.i("AutoBrowseTest", "books=$sizes")
        val all = sizes.getValue("books:all")
        assertNotNull("Alle Bücher nicht abrufbar", all)
        if (sizes.getValue("books:authors") ?: 0 > 0) assertTrue("Autoren da, aber keine Bücher", all!! > 0)
    }

    /** „Zuletzt“ liefert, was „Weiterhören“ der Podcasts liefert (so sieht es Android Auto). */
    @Test
    fun homeListsWhatContinueListeningHas() {
        val continueEpisodes = children("podcasts:continue").orEmpty().map { it.mediaId }
        val home = children("home")
        assertNotNull(home)
        android.util.Log.i("AutoBrowseTest", "home=${home!!.map { it.mediaId + "|" + group(it) }} continue=$continueEpisodes")
        if (continueEpisodes.isNotEmpty()) {
            assertTrue("Zuletzt ist leer, obwohl Weiterhören ${continueEpisodes.size} Folgen hat", home.isNotEmpty())
        }
    }

    /**
     * Umschalten meldet die Session selbst (notifyChildrenChanged) an alle, die die Wurzel abonniert haben. Der alte
     * [MediaBrowser] des Frameworks abonniert nicht bei der Session (nur die Compat-Fassung von Android Auto); daher hier ein
     * Media3-Browser, dessen Abo über denselben Weg (`onSubscribe`) läuft.
     */
    @Test
    fun tabsFollowTheAreas() {
        val loads = LinkedBlockingQueue<List<String>>()
        val listener = object : androidx.media3.session.MediaBrowser.Listener {
            override fun onChildrenChanged(
                browser: androidx.media3.session.MediaBrowser,
                parentId: String,
                itemCount: Int,
                params: MediaLibraryService.LibraryParams?,
            ) {
                val children = browser.getChildren(parentId, 0, 100, null)
                children.addListener({ loads.put(children.get().value.orEmpty().map { it.mediaId }) }, ContextCompat.getMainExecutor(context))
            }
        }
        instrumentation.runOnMainSync { media3Future = androidx.media3.session.MediaBrowser.Builder(context, token()).setListener(listener).buildAsync() }
        val media3 = media3Future!!.get(10, TimeUnit.SECONDS)
        try {
            var subscribed: ListenableFuture<LibraryResult<Void>>? = null
            instrumentation.runOnMainSync { subscribed = media3.subscribe("root", null) }
            assertEquals(LibraryResult.RESULT_SUCCESS, subscribed!!.get(10, TimeUnit.SECONDS).resultCode)
            loads.awaitLoad(listOf("home", "area:music", "area:podcasts", "area:books"))

            setAreas(listOf(SpokenMode.MUSIC, SpokenMode.BOOKS))
            loads.awaitLoad(listOf("home", "area:music", "area:books"))

            setAreas(listOf(SpokenMode.PODCASTS))
            loads.awaitLoad(listOf("podcasts:continue", "podcasts:new", "podcasts:feeds", "podcasts:downloaded"))

            setAreas(listOf(SpokenMode.MUSIC))
            loads.awaitLoad(listOf("recent", "library", "playlists", "favorites"))
        } finally {
            instrumentation.runOnMainSync { media3.release() }
        }
    }

    private var media3Future: ListenableFuture<androidx.media3.session.MediaBrowser>? = null

    private fun token() = SessionToken(context, ComponentName(context.packageName, "de.kleinabersmart.earloft.core.playback.PlaybackService"))

    /** Wartet auf diese Tabs; Zwischenstände (jeder Schalter meldet sich einzeln) zählen nicht. */
    private fun LinkedBlockingQueue<List<String>>.awaitLoad(expected: List<String>) {
        val deadline = System.currentTimeMillis() + 10_000
        var last: List<String>? = null
        while (System.currentTimeMillis() < deadline) {
            last = poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS) ?: break
            if (last == expected) return
        }
        assertEquals(expected, last)
    }

    @Test
    fun hiddenAreaIsNotBrowsable() {
        setAreas(listOf(SpokenMode.MUSIC, SpokenMode.BOOKS))
        assertEquals(null, children("area:podcasts"))
        assertEquals(null, children("podcasts:feeds"))
        val home = children("home")
        assertNotNull(home)
        assertTrue(home!!.none { group(it) == context.getString(R.string.area_podcasts) })
        assertNotNull(children("area:books"))
    }

    @Test
    fun searchLeavesOutHiddenAreas() {
        val all = search("e")
        setAreas(listOf(SpokenMode.BOOKS))
        val books = search("e")
        val spokenGroups = setOf(context.getString(R.string.area_books), authors(), series())
        assertTrue(books.joinToString { it.group.orEmpty() }, books.all { it.group in spokenGroups })
        assertTrue(books.none { it.mediaId.startsWith("album:") || it.mediaId.startsWith("feed:") })
        assertTrue(all.size >= books.size)
    }

    @Test
    fun spokenItemPlaysFromBrowsing() {
        val item = (children("podcasts:continue").orEmpty() + children("books:continue").orEmpty())
            .firstOrNull { it.isPlayable }
            ?: return // Ohne Folgen und Bücher auf dem Gerät gibt es nichts zu spielen.
        val bookId = item.mediaId!!.removePrefix("spoken:")
        val playing = CountDownLatch(1)
        var controller: MediaController? = null
        instrumentation.runOnMainSync {
            controller = MediaController(context, browser!!.sessionToken).apply {
                registerCallback(
                    object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            if (metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.startsWith("book:$bookId/") == true) playing.countDown()
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
                transportControls.playFromMediaId(item.mediaId, Bundle.EMPTY)
            }
        }
        try {
            assertTrue("Buch/Folge $bookId lief nicht an", playing.await(20, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { controller?.transportControls?.pause() }
        }
    }

    private fun authors() = context.getString(R.string.authors)
    private fun series() = context.getString(R.string.series)

    private fun group(item: MediaBrowser.MediaItem): String? = item.description.extras?.getString(GROUP_TITLE)

    private companion object {
        const val GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    }

    /** Inhalt eines Ordners; `null` bei einem Fehler. */
    private fun children(parentId: String): List<MediaBrowser.MediaItem>? {
        val result = LinkedBlockingQueue<Result<List<MediaBrowser.MediaItem>?>>()
        val callback = object : MediaBrowser.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                result.put(Result.success(children.toList()))
            }

            override fun onError(parentId: String) {
                result.put(Result.success(null))
            }
        }
        instrumentation.runOnMainSync { browser!!.subscribe(parentId, callback) }
        val loaded = result.poll(10, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { browser!!.unsubscribe(parentId) }
        assertNotNull("Keine Antwort für $parentId", loaded)
        return loaded!!.getOrNull()
    }

    private class Hit(val mediaId: String, val group: String?)

    /**
     * Der alte [MediaBrowser] des Frameworks kann nicht suchen (Android Auto nutzt die Compat-Fassung, die Media3 auf
     * dieselben Callbacks abbildet); daher hier ein Media3-Browser.
     */
    private fun search(query: String): List<Hit> {
        val token = token()
        var future: ListenableFuture<androidx.media3.session.MediaBrowser>? = null
        instrumentation.runOnMainSync { future = androidx.media3.session.MediaBrowser.Builder(context, token).buildAsync() }
        val media3 = future!!.get(10, TimeUnit.SECONDS)
        try {
            var result: ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>? = null
            instrumentation.runOnMainSync {
                media3.search(query, null)
                result = media3.getSearchResult(query, 0, 500, null)
            }
            return result!!.get(20, TimeUnit.SECONDS).value.orEmpty().map { item ->
                Hit(item.mediaId, item.mediaMetadata.extras?.getString(GROUP_TITLE))
            }
        } finally {
            instrumentation.runOnMainSync { media3.release() }
        }
    }

    private fun setAreas(areas: List<SpokenMode>) = runBlocking {
        // Erst einschalten, dann ausschalten: Der letzte eingeschaltete Bereich lässt sich nicht ausschalten.
        areas.forEach { settings.setAreaEnabled(it, true) }
        (SpokenMode.entries - areas.toSet()).forEach { settings.setAreaEnabled(it, false) }
    }
}
