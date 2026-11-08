package de.kleinabersmart.earloft.core.network.audiobookshelf

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Setzt `Authorization: Bearer <API-Key>` für Anfragen an bekannte Audiobookshelf-Server (Konzept 12.1).
 *
 * Als *Network*-Interceptor registriert, damit der Header nicht in Cache-Schlüsseln landet; die URLs der
 * Medien (`/api/items/<id>/file/<ino>`) enthalten dadurch keine Secrets.
 */
class AbsAuthInterceptor @Inject constructor(
    private val registry: AbsServerRegistry,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val server = registry.forUrl(original.url) ?: return chain.proceed(original)
        if (original.header("Authorization") != null) return chain.proceed(original)
        val response = chain.proceed(original.newBuilder().header("Authorization", "Bearer ${server.apiKey}").build())
        return response.newBuilder().request(original).build()
    }
}
