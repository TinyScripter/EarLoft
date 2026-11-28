package de.kleinabersmart.earloft.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabResetGuardTest {

    @Test
    fun `without an open form the tap resets the tab right away`() {
        assertFalse(TabResetGuard().intercept())
    }

    @Test
    fun `an open form with input asks first and only discarding resets the tab`() {
        var resets = 0
        var asked = 0
        val guard = TabResetGuard { resets++ }
        guard.register { asked++ }

        assertTrue(guard.intercept())
        assertEquals(1, asked)
        assertEquals(0, resets)

        guard.proceed()
        assertEquals(1, resets)
    }

    @Test
    fun `a form that was closed or saved no longer intercepts`() {
        val guard = TabResetGuard()
        val ask = {}
        guard.register(ask)
        guard.unregister(ask)

        assertFalse(guard.intercept())
    }
}
