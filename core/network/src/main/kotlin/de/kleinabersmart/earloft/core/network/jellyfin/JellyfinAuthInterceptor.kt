package de.kleinabersmart.earloft.core.network.jellyfin

import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Setzt `Authorization: MediaBrowser … Token="…"` für Anfragen an bekannte Jellyfin-Server (Konzept 14).
 *
 * Als *Network*-Interceptor registriert wie [de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthInterceptor]:
 * Stream-, Download- und Cover-URLs enthalten keine Secrets, Cache-Schlüssel bleiben stabil. Der Server kommt aus dem
 * Request-Tag [JellyfinServer] (gesetzt von [JellyfinApi], nach einer Weiterleitung nur auf denselben Host) oder aus der
 * [JellyfinServerRegistry].
 */
class JellyfinAuthInterceptor @Inject constructor(
    private val registry: JellyfinServerRegistry,
    private val clientInfo: JellyfinClientInfo,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(AUTHORIZATION) != null) return chain.proceed(original)
        val server = original.tag(JellyfinServer::class.java)?.takeIf { original.url.acceptsCredentialsOf(it.baseUrl) }
            ?: registry.forUrl(original.url)
            ?: return chain.proceed(original)
        val authorized = original.newBuilder().header(AUTHORIZATION, clientInfo.authorization(server.deviceId, server.token)).build()
        return chain.proceed(authorized).newBuilder().request(original).build()
    }

    internal companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
