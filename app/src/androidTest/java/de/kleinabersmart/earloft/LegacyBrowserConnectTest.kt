package de.kleinabersmart.earloft

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android Auto verbindet sich über den alten [MediaBrowser]. Media3 beantwortet dessen `onGetRoot` auf dem Main-Thread und
 * wartet dort ohne Zeitlimit auf `onGetLibraryRoot`; ein Future, das selbst den Main-Thread braucht, hängt die App auf (ANR).
 */
@RunWith(AndroidJUnit4::class)
class LegacyBrowserConnectTest {

    @Test
    fun legacyBrowserGetsRoot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        var browser: MediaBrowser? = null
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
        try {
            assertTrue("Keine Verbindung innerhalb von 10 s", connected.await(10, TimeUnit.SECONDS))
            assertEquals("root", browser!!.root)
        } finally {
            // Nicht runOnMainSync: Hängt der Main-Thread, bliebe der Test sonst selbst stehen.
            Handler(Looper.getMainLooper()).post { browser?.disconnect() }
        }
    }
}
