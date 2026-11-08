package de.kleinabersmart.earloft.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wartet in echter Zeit auf [block]. In `runTest` liefe `withTimeout` in virtueller Zeit ab und schlüge sofort an, während
 * Room noch auf seinem eigenen Thread arbeitet.
 */
suspend fun <T> awaitReal(timeout: Duration = 5.seconds, block: suspend () -> T): T =
    withContext(Dispatchers.Default) { withTimeout(timeout) { block() } }
