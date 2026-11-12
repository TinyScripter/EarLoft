package de.kleinabersmart.earloft.core.network.audiobookshelf

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Setzt `Authorization: Bearer <Token>` für Anfragen an bekannte Audiobookshelf-Server (Konzept 12.1, 12.11).
 *
 * Als *Network*-Interceptor registriert, damit der Header nicht in Cache-Schlüsseln landet; die URLs der
 * Medien (`/api/items/<id>/file/<ino>`) enthalten dadurch keine Secrets. Welcher Token ging, steht als [AbsSentToken] an der
 * Anfrage der Antwort, damit [AbsTokenAuthenticator] bei 401 weiß, welcher abgelehnt wurde.
 */
class AbsAuthInterceptor @Inject constructor(
    private val registry: AbsServerRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val server = registry.forUrl(original.url) ?: return chain.proceed(original)
        if (original.header("Authorization") != null) return chain.proceed(original)
        val response = chain.proceed(original.newBuilder().header("Authorization", "Bearer ${server.token}").build())
        return response.newBuilder().request(original.newBuilder().tag(AbsSentToken::class.java, AbsSentToken(server.token)).build()).build()
    }
}

/** Der Token, den [AbsAuthInterceptor] gesetzt hat. */
internal class AbsSentToken(val token: String)
