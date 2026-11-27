package de.kleinabersmart.earloft

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.session.MediaConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import de.kleinabersmart.earloft.core.playback.R as PlaybackR

/**
 * Android Auto opens the app before anything is set up: instead of empty tabs the platform session reports "Set up EarLoft on your
 * phone once you have safely stopped" with "Open on phone" (car app quality VI-1). Needs an app without any content; run it after
 * `pm clear` with `-e freshInstall true`, otherwise it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class CarSetupHintTest {

    @Test
    fun emptyAppAsksToSetUpOnThePhone() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("freshInstall") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        val tabs = CountDownLatch(1)
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
            // Like Android Auto: the tabs are loaded right after connecting.
            instrumentation.runOnMainSync {
                browser!!.subscribe(
                    browser!!.root,
                    object : MediaBrowser.SubscriptionCallback() {
                        override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                            // The tabs stay, each with its white symbol.
                            assertTrue(children.isNotEmpty())
                            assertTrue(children.all { it.description.iconUri?.scheme == "android.resource" })
                            tabs.countDown()
                        }
                    },
                )
            }
            assertTrue("No tabs within 10 s", tabs.await(10, TimeUnit.SECONDS))

            val controller = MediaController(context, browser!!.sessionToken)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var state: PlaybackState? = controller.playbackState
            while (state?.state != PlaybackState.STATE_ERROR && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
                state = controller.playbackState
            }
            assertEquals(PlaybackState.STATE_ERROR, state?.state)
            assertEquals(context.getString(PlaybackR.string.phone_action_set_up), state?.errorMessage?.toString())
            val extras = state?.extras
            assertEquals(
                context.getString(PlaybackR.string.phone_action_open),
                extras?.getString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT),
            )
            @Suppress("DEPRECATION")
            assertNotNull(extras?.getParcelable<android.app.PendingIntent>(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT))
        } finally {
            Handler(Looper.getMainLooper()).post { browser?.disconnect() }
        }
    }
}
