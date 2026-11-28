package de.kleinabersmart.earloft.core.data.spoken

/**
 * Sends the pending [entries] one by one with [push] (`false` = not sent), only those of [serverId] if given. After a failure the
 * rest of that server waits for the next run: a server only reachable at home would otherwise let every entry run into its
 * timeout and hold up the sync of the others for minutes (review5 P30). [serverOf]: the server an entry belongs to, `null` if none.
 * @return `true` if nothing is left open.
 */
internal suspend fun <T> pushPerServer(
    entries: List<T>,
    serverId: String?,
    serverOf: suspend (T) -> String?,
    push: suspend (T) -> Boolean,
): Boolean {
    val failed = HashSet<String>()
    var done = true
    for (entry in entries) {
        val server = serverOf(entry)
        if (serverId != null && server != serverId) continue
        if (server != null && server in failed) {
            done = false
            continue
        }
        if (!push(entry)) {
            done = false
            server?.let(failed::add)
        }
    }
    return done
}
