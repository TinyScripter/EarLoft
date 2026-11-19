package de.kleinabersmart.earloft.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Waits for [block] in real time. In `runTest`, `withTimeout` would run in virtual time and fire immediately while
 * Room is still working on its own thread.
 */
suspend fun <T> awaitReal(timeout: Duration = 5.seconds, block: suspend () -> T): T =
    withContext(Dispatchers.Default) { withTimeout(timeout) { block() } }
