package de.kleinabersmart.earloft

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** API 32: the last version on which reading one extra unpacks all of them. */
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
