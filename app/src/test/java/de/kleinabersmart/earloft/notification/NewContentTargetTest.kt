package de.kleinabersmart.earloft.notification

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.notification.NewContentTarget.Companion.putNewContentTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewContentTargetTest {

    private fun roundTrip(target: NewContentTarget) =
        NewContentTarget.from(Intent(NewContentTarget.ACTION_OPEN).putNewContentTarget(target))

    @Test
    fun `a show from ARD Sounds survives the intent`() {
        val target = NewContentTarget(SpokenMode.BOOKS, ardShowId = "urn:ard:show:reihe", ardShowTitle = "Kult!")

        assertEquals(target, roundTrip(target))
    }

    @Test
    fun `a foreign start with an unknown class as a target value opens nothing instead of crashing`() {
        // From API 33 on, the bundle's values stay packed until asked for by type; reading "mode" as a string must not unpack it.
        val target = Bundle().apply { putParcelable("mode", Unreadable()) }
        val intent = Intent(NewContentTarget.ACTION_OPEN)
            .apply { replaceExtras(packed(Bundle().apply { putBundle("de.kleinabersmart.earloft.NEW_CONTENT_TARGET", target) })) }

        assertNull(NewContentTarget.from(intent))
    }

    @Test
    fun `a book target has no show`() {
        val restored = roundTrip(NewContentTarget(SpokenMode.BOOKS, bookId = 7))!!

        assertEquals(7L, restored.bookId)
        assertNull(restored.ardShowId)
        assertNull(restored.ardShowTitle)
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
