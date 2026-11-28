package de.kleinabersmart.earloft.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FindNowOrInForegroundTest {

    @Test
    fun `a device found at once needs no second look`() = runTest {
        var looks = 0
        val found = findNowOrInForeground(inForeground = { false }, awaitForeground = { error("must not wait") }) {
            looks++
            "device"
        }
        assertEquals("device", found)
        assertEquals(1, looks)
    }

    @Test
    fun `in the foreground a device not found stays not found`() = runTest {
        var looks = 0
        val found = findNowOrInForeground<String>(inForeground = { true }, awaitForeground = { error("must not wait") }) {
            looks++
            null
        }
        assertNull(found)
        assertEquals(1, looks)
    }

    @Test
    fun `in the background it looks again once the app is opened`() = runTest {
        val opened = CompletableDeferred<Unit>()
        var looks = 0
        val result = async {
            findNowOrInForeground(inForeground = { false }, awaitForeground = { opened.await() }) {
                looks++
                if (opened.isCompleted) "device" else null
            }
        }
        runCurrent()
        assertFalse("Waits for the app to be opened", result.isCompleted)
        assertEquals(1, looks)

        opened.complete(Unit)
        assertEquals("device", result.await())
        assertEquals(2, looks)
    }
}
