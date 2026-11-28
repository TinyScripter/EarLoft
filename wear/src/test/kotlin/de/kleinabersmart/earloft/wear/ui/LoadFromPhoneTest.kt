package de.kleinabersmart.earloft.wear.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `each look waits twice as long, up to the limit`() = runTest {
        val answers = ArrayDeque(listOf(null, null, null, null, "tracks"))
        val looks = mutableListOf<Long>()

        loadFromPhone(
            phoneIsThere = { looks += testScheduler.currentTime; true },
            onUnreachable = {},
            retryMs = 1_000,
            maxRetryMs = 4_000,
        ) { answers.removeFirst() }

        assertEquals(listOf(1_000L, 3_000L, 7_000L, 11_000L), looks)
    }
}
