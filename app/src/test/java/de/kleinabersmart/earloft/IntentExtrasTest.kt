package de.kleinabersmart.earloft

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.robolectric.Robolectric
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** API 32: the last version on which reading one extra unpacks all of them; single tests on API 34, where values stay packed. */
@Config(application = Application::class, sdk = [32])
@RunWith(RobolectricTestRunner::class)
class IntentExtrasTest {

    @Test
    fun `an extra of an unknown class no longer breaks reading the intent`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/feed.xml"))
            .apply { replaceExtras(packed(Bundle().apply { putParcelable("foreign", Unreadable()) })) }
        // Without the fix, the first read throws (as on a device below API 33).
        assertThrows(RuntimeException::class.java) { Intent(intent).getBooleanExtra("any", false) }

        intent.dropUnreadableExtras()

        assertFalse(intent.getBooleanExtra("any", false))
        assertNull(intent.extras)
        assertEquals("https://example.org/feed.xml", intent.dataString)
    }

    @Test
    fun `a share with an extra of an unknown class reaches the app without it instead of crashing the trampoline`() {
        val shared = Intent(Intent.ACTION_SEND).apply {
            replaceExtras(packed(Bundle().apply { putString(Intent.EXTRA_TEXT, "link"); putParcelable("foreign", Unreadable()) }))
        }
        // Without dropping them first, adding the launch ID unpacks all extras and throws (as on a device below API 33).
        assertThrows(RuntimeException::class.java) { Intent(shared).withLaunchId() }

        val forwarded = shared.forwardedToMainActivity(ApplicationProvider.getApplicationContext())

        assertEquals(Intent.ACTION_SEND, forwarded.action)
        assertNotNull(forwarded.getStringExtra(EXTRA_LAUNCH_ID))
        assertNull(forwarded.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a readable share reaches the app with its text and a launch ID`() {
        val shared = Intent(Intent.ACTION_SEND).apply { replaceExtras(packed(Bundle().apply { putString(Intent.EXTRA_TEXT, "link") })) }

        val forwarded = shared.forwardedToMainActivity(ApplicationProvider.getApplicationContext())

        assertEquals("link", forwarded.getStringExtra(Intent.EXTRA_TEXT))
        assertNotNull(forwarded.getStringExtra(EXTRA_LAUNCH_ID))
    }

    @Test
    fun `readable extras stay`() {
        val intent = Intent(Intent.ACTION_SEND).apply { replaceExtras(packed(Bundle().apply { putString(Intent.EXTRA_TEXT, "link") })) }

        intent.dropUnreadableExtras()

        assertEquals("link", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a bundle extra with an unknown class inside is dropped instead of breaking the start`() {
        val target = Bundle().apply { putParcelable("foreign", Unreadable()) }
        val intent = Intent("de.kleinabersmart.earloft.OPEN_NEW_CONTENT").apply { replaceExtras(packed(Bundle().apply { putBundle("target", target) })) }

        assertNull(intent.safeBundleExtra("target"))
    }

    @Test
    fun `a readable bundle extra stays`() {
        val intent = Intent(Intent.ACTION_VIEW).apply { replaceExtras(packed(Bundle().apply { putBundle("target", Bundle().apply { putString("mode", "BOOKS") }) })) }

        assertEquals("BOOKS", intent.safeBundleExtra("target")?.getString("mode"))
    }

    @Test
    @Config(sdk = [34])
    fun `from API 33 an extra of an unknown class is dropped before the activity's view models read it, the others stay`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            replaceExtras(packed(Bundle().apply { putString(Intent.EXTRA_TEXT, "link"); putParcelable("foreign", Unreadable()) }))
        }
        // Reading one extra no longer unpacks the others here.
        assertEquals("link", Intent(intent).getStringExtra(Intent.EXTRA_TEXT))

        intent.dropUnreadableExtras()

        // `ComponentActivity` hands the extras to every `SavedStateHandle` as default arguments, which reads each value.
        val activity = Robolectric.buildActivity(ComponentActivity::class.java, intent).setup().get()
        val handle = ViewModelProvider(activity)[WithState::class.java].handle
        assertEquals(setOf(Intent.EXTRA_TEXT), handle.keys())
        assertEquals("link", handle.get<String>(Intent.EXTRA_TEXT))
        assertFalse(activity.intent.hasExtra("foreign"))
        assertEquals(Intent.ACTION_SEND, activity.intent.action)
    }

    @Test
    @Config(sdk = [34])
    fun `from API 33 a share with an extra of an unknown class reaches the app with its text`() {
        val shared = Intent(Intent.ACTION_SEND).apply {
            replaceExtras(packed(Bundle().apply { putString(Intent.EXTRA_TEXT, "link"); putParcelable("foreign", Unreadable()) }))
        }

        val forwarded = shared.forwardedToMainActivity(ApplicationProvider.getApplicationContext())

        assertEquals("link", forwarded.getStringExtra(Intent.EXTRA_TEXT))
        assertNotNull(forwarded.getStringExtra(EXTRA_LAUNCH_ID))
    }

    @Test
    @Config(sdk = [34])
    fun `from API 33 readable extras stay and reach the view models`() {
        val intent = Intent(Intent.ACTION_SEND).apply { replaceExtras(packed(Bundle().apply { putString(Intent.EXTRA_TEXT, "link") })) }

        intent.dropUnreadableExtras()

        val activity = Robolectric.buildActivity(ComponentActivity::class.java, intent).setup().get()
        assertEquals("link", ViewModelProvider(activity)[WithState::class.java].handle.get<String>(Intent.EXTRA_TEXT))
    }

    /** Like the app's Hilt view models in the activity scope: gets the intent's extras as default arguments. */
    class WithState(val handle: SavedStateHandle) : ViewModel()

    /** [bundle] as it arrives from another app: still packed, unpacked on first access. */
    private fun packed(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    /** Stands in for a class of another app: without a `CREATOR`, it cannot be read back. */
    private class Unreadable : Parcelable {
        override fun describeContents() = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }
}
