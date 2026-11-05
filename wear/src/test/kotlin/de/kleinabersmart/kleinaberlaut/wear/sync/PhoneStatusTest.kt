package de.kleinabersmart.kleinaberlaut.wear.sync

import com.google.android.gms.wearable.Node
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneStatusTest {

    private fun node(id: String, nearby: Boolean = true) = object : Node {
        override fun getId() = id
        override fun getDisplayName() = id
        override fun isNearby() = nearby
    }

    @Test
    fun `no connected device means not connected even with a stale phone info`() {
        assertEquals(PhoneStatus.NotConnected, phoneStatusOf(emptyList(), withApp = setOf("phone")))
    }

    @Test
    fun `connected phone without the app`() {
        assertEquals(PhoneStatus.AppMissing("phone"), phoneStatusOf(listOf(node("phone")), withApp = emptySet()))
    }

    @Test
    fun `connected phone with the app`() {
        assertEquals(PhoneStatus.Connected("phone"), phoneStatusOf(listOf(node("phone")), withApp = setOf("phone")))
    }

    @Test
    fun `app on a device that is no longer connected does not count`() {
        val status = phoneStatusOf(listOf(node("new")), withApp = setOf("old"))

        assertEquals(PhoneStatus.AppMissing("new"), status)
    }

    @Test
    fun `nearby devices are preferred`() {
        val far = node("far", nearby = false)
        val near = node("near", nearby = true)

        assertEquals(PhoneStatus.Connected("near"), phoneStatusOf(listOf(far, near), withApp = setOf("far", "near")))
        assertEquals(PhoneStatus.AppMissing("near"), phoneStatusOf(listOf(far, near), withApp = emptySet()))
    }
}
