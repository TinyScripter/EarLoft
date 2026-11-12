package de.kleinabersmart.earloft.core.network.audiobookshelf

import dagger.Lazy
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/** Erneuert abgelaufene Access-Tokens von Servern mit Anmeldung per OpenID (Konzept 12.11). */
interface AbsTokenRefresher {
    /**
     * Blockiert, bis feststeht, womit eine mit [rejected] abgelehnte Anfrage wiederholt werden soll: dem schon erneuerten oder
     * einem frisch erneuerten Access-Token. `null`: nicht wiederholen (API-Key, kein Refresh-Token, fremder Token, Fehler).
     */
    fun renewBlocking(serverId: String, rejected: String): String?
}

/**
 * Wiederholt eine mit 401 abgelehnte Anfrage an einen Audiobookshelf-Server einmal mit erneuertem Access-Token. Gilt für alle
 * Anfragen des gemeinsamen Clients: API, Cover und Streams.
 */
class AbsTokenAuthenticator @Inject constructor(
    private val registry: AbsServerRegistry,
    // Lazy: Die Erneuerung braucht selbst den Client, in dem dieser Authenticator steckt.
    private val refresher: Lazy<AbsTokenRefresher>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (generateSequence(response.priorResponse) { it.priorResponse }.any { it.code == 401 }) return null
        val request = response.request
        val server = registry.forUrl(request.url) ?: return null
        val rejected = request.header("Authorization")?.removePrefix("Bearer ")
            ?: request.tag(AbsSentToken::class.java)?.token
            ?: return null
        val token = refresher.get().renewBlocking(server.serverId, rejected) ?: return null
        return request.newBuilder().header("Authorization", "Bearer $token").build()
    }
}
