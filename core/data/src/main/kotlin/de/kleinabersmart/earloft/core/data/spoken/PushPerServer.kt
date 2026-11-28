package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import java.io.IOException

/** Outcome of sending one pending entry to its server. */
internal enum class Pushed {
    DONE,

    /** The server answered but refused this entry (e.g. 404 for a book deleted there): the next entry may still go through. */
    REJECTED,

    /** No answer or no sign-in: the server's other entries would fail the same way. */
    UNREACHABLE,
}

/** [Pushed] for a failed request: an answer from the server rejects only this entry. */
internal fun IOException.pushFailure(): Pushed = if (this is AbsException) Pushed.REJECTED else Pushed.UNREACHABLE

/**
 * Sends the pending [entries] one by one with [push], only those of [serverId] if given. Once a server is unreachable, the rest
 * of that server waits for the next run: a server only reachable at home would otherwise let every entry run into its timeout and
 * hold up the sync of the others for minutes (review5 P30). An entry the server rejects holds back nothing else.
 * [serverOf]: the server an entry belongs to, `null` if none.
 * @return `true` if nothing is left open.
 */
internal suspend fun <T> pushPerServer(
    entries: List<T>,
    serverId: String?,
    serverOf: suspend (T) -> String?,
    push: suspend (T) -> Pushed,
): Boolean {
    val unreachable = HashSet<String>()
    var done = true
    for (entry in entries) {
        val server = serverOf(entry)
        if (serverId != null && server != serverId) continue
        if (server != null && server in unreachable) {
            done = false
            continue
        }
        when (push(entry)) {
            Pushed.DONE -> Unit
            Pushed.REJECTED -> done = false
            Pushed.UNREACHABLE -> {
                done = false
                server?.let(unreachable::add)
            }
        }
    }
    return done
}
