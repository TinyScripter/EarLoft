package de.kleinabersmart.earloft.core.data.flow

import android.database.sqlite.SQLiteFullException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A long-lived collector goes on after a failure instead of ending silently (review5 P20). */
@RunWith(RobolectricTestRunner::class)
class RestartOnErrorTest {

    @Test
    fun `a failing query is collected again`() = runBlocking {
        var attempts = 0
        val source = flow {
            attempts++
            if (attempts == 1) throw SQLiteFullException("disk full")
            emit("state $attempts")
        }

        assertEquals(listOf("state 2"), source.restartOnError("Test", "Query", delayMs = 1).take(1).toList())
    }

    @Test
    fun `a failing side effect in onEach starts the flow again`() = runBlocking {
        var calls = 0
        val delivered = mutableListOf<Int>()
        val source = flow { emit(1); emit(2) }

        source
            .onEach { if (calls++ == 0) throw IllegalStateException("Data Layer unavailable") else delivered += it }
            .restartOnError("Test", "Publishing", delayMs = 1)
            .take(2)
            .toList()

        assertEquals(listOf(1, 2), delivered)
    }
}
