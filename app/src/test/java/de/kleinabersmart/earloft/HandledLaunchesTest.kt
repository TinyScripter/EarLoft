package de.kleinabersmart.earloft

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class HandledLaunchesTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun launch(id: String? = null) = Intent(Intent.ACTION_SEND).apply { id?.let { putExtra(EXTRA_LAUNCH_ID, it) } }

    @Test
    fun `a start with a launch ID runs once, also for a new instance after the process ended`() {
        val intent = Intent(Intent.ACTION_SEND).withLaunchId()
        assertTrue(HandledLaunches(context).claim(intent))
        assertFalse(HandledLaunches(context).claim(Intent(intent)))
    }

    @Test
    fun `a start without a launch ID runs, unless only the trampolines deliver its action`() {
        val launches = HandledLaunches(context)
        assertTrue(launches.claim(Intent(Intent.ACTION_MAIN)))
        assertTrue(launches.claim(Intent(Intent.ACTION_MAIN)))
        assertTrue(launches.claim(Intent("de.kleinabersmart.earloft.NEW_EPISODE_PLAY")))
        // After an app update the system recreates the activity with the root intent of the task, but without its extras.
        assertFalse(launches.claim(launch()))
        assertFalse(launches.claim(launch(id = "")))
        assertFalse(launches.claim(Intent(Intent.ACTION_VIEW, Uri.parse("feed://example.org/feed.xml"))))
        assertFalse(launches.claim(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)))
    }

    @Test
    fun `every start gets its own ID, and a caller's ID is replaced`() {
        val first = launch(id = "chosen-by-caller").withLaunchId().getStringExtra(EXTRA_LAUNCH_ID)
        val second = launch().withLaunchId().getStringExtra(EXTRA_LAUNCH_ID)
        assertNotEquals("chosen-by-caller", first)
        assertNotEquals(first, second)
    }

    @Test
    fun `only the latest IDs are kept`() {
        val launches = HandledLaunches(context)
        val ids = (1..25).map { "id-$it" }
        ids.forEach { assertTrue(launches.claim(launch(it))) }
        // The oldest five are forgotten, the newer ones still count as handled.
        assertEquals(listOf(true, false), listOf(launches.claim(launch("id-1")), launches.claim(launch("id-25"))))
    }
}
