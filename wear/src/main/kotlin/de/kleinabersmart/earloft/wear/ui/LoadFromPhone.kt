package de.kleinabersmart.earloft.wear.ui

import kotlinx.coroutines.delay

/**
 * Loads a list from the phone with [load] (`null` = no answer). Without an answer [onUnreachable] says so, and it is tried again as
 * soon as the phone is there ([phoneIsThere], looked at every [retryMs]): a list used to stay "phone unreachable" until the page
 * was opened again (review5 P69). Each look doubles the wait, up to [maxRetryMs]: a phone that is there but doesn't answer (old
 * app, huge list) or one that stays away would otherwise be asked every few seconds for as long as the page lives.
 */
internal suspend fun <T : Any> loadFromPhone(
    phoneIsThere: suspend () -> Boolean,
    onUnreachable: () -> Unit,
    retryMs: Long = PHONE_RETRY_MS,
    maxRetryMs: Long = MAX_PHONE_RETRY_MS,
    load: suspend () -> T?,
): T {
    var wait = retryMs
    while (true) {
        load()?.let { return it }
        onUnreachable()
        do {
            delay(wait)
            wait = (wait * 2).coerceAtMost(maxRetryMs)
        } while (!phoneIsThere())
    }
}

private const val PHONE_RETRY_MS = 10_000L
private const val MAX_PHONE_RETRY_MS = 5 * 60_000L
