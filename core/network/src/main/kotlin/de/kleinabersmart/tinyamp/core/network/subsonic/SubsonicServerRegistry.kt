package de.kleinabersmart.tinyamp.core.network.subsonic

import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bekannte Server, damit [SubsonicAuthInterceptor] auch Anfragen authentifizieren kann, die nicht
 * von [SubsonicApi] stammen (Media3-Streams, Cover).
 */
@Singleton
class SubsonicServerRegistry @Inject constructor() {

    @Volatile
    private var servers: Map<String, SubsonicServer> = emptyMap()

    fun replaceAll(newServers: Collection<SubsonicServer>) {
        servers = newServers.associateBy { it.sourceId }
    }

    fun register(server: SubsonicServer) {
        servers = servers + (server.sourceId to server)
    }

    fun unregister(sourceId: String) {
        servers = servers - sourceId
    }

    operator fun get(sourceId: String): SubsonicServer? = servers[sourceId]

    fun forUrl(url: HttpUrl): SubsonicServer? = servers.values.firstOrNull { it.owns(url) }
}
