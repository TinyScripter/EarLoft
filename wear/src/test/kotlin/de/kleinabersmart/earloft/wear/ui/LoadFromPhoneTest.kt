package de.kleinabersmart.earloft.wear.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A list of the phone loads again once the phone is back (review5 P69). */
class LoadFromPhoneTest {

    @Test
    fun `an unanswered request is tried again once the phone is there`() = runTest {
        val answers = ArrayDeque(listOf(null, null, "tracks"))
        var phoneChecks = 0
        var unreachableShown = 0

        val result = loadFromPhone(
            phoneIsThere = { ++phoneChecks >= 3 },
            onUnreachable = { unreachableShown++ },
            retryMs = 1_000,
        ) { answers.removeFirst() }

        assertEquals("tracks", result)
        assertEquals(2, unreachableShown)
        assertEquals(4, phoneChecks)
    }
}
