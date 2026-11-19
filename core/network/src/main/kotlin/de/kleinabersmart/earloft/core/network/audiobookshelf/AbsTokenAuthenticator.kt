package de.kleinabersmart.earloft.core.network.audiobookshelf

import dagger.Lazy
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject

/** Refreshes expired access tokens of servers signed in via OpenID (concept 12.11). */
interface AbsTokenRefresher {
    /**
     * Blocks until it's clear what a request rejected with [rejected] should be retried with: the already refreshed or
     * a freshly refreshed access token. `null`: don't retry (API key, no refresh token, foreign token, error).
     */
    fun renewBlocking(serverId: String, rejected: String): String?
}

/**
 * Retries a request to an Audiobookshelf server rejected with 401 once with a refreshed access token. Applies to all
 * requests of the shared client: API, covers and streams.
 */
class AbsTokenAuthenticator @Inject constructor(
    private val registry: AbsServerRegistry,
    // Lazy: the refresh itself needs the client this authenticator is part of.
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
