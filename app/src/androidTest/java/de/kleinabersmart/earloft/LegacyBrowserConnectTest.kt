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
 * Android Auto connects via the legacy [MediaBrowser]. Media3 answers its `onGetRoot` on the main thread and
 * waits there without a timeout for `onGetLibraryRoot`; a future that itself needs the main thread hangs the app (ANR).
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
            assertTrue("No connection within 10 s", connected.await(10, TimeUnit.SECONDS))
            assertEquals("root", browser!!.root)
        } finally {
            // Not runOnMainSync: if the main thread hangs, the test itself would otherwise get stuck.
            Handler(Looper.getMainLooper()).post { browser?.disconnect() }
        }
    }
}
