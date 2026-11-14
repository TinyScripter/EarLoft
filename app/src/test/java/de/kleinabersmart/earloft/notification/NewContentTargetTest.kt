package de.kleinabersmart.earloft.notification

import android.content.Intent
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
    fun `a book target has no show`() {
        val restored = roundTrip(NewContentTarget(SpokenMode.BOOKS, bookId = 7))!!

        assertEquals(7L, restored.bookId)
        assertNull(restored.ardShowId)
        assertNull(restored.ardShowTitle)
    }
}
