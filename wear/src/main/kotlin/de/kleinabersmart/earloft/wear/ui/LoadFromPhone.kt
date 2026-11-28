package de.kleinabersmart.earloft.wear.ui

import kotlinx.coroutines.delay

/**
 * Loads a list from the phone with [load] (`null` = no answer). Without an answer [onUnreachable] says so, and it is tried again as
 * soon as the phone is there ([phoneIsThere], looked at every [retryMs]): a list used to stay "phone unreachable" until the page
 * was opened again (review5 P69).
 */
internal suspend fun <T : Any> loadFromPhone(
    phoneIsThere: suspend () -> Boolean,
    onUnreachable: () -> Unit,
    retryMs: Long = PHONE_RETRY_MS,
    load: suspend () -> T?,
): T {
    while (true) {
        load()?.let { return it }
        onUnreachable()
        do delay(retryMs) while (!phoneIsThere())
    }
}

private const val PHONE_RETRY_MS = 10_000L
