package de.kleinabersmart.earloft.core.network.subsonic

import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Known servers, so that [SubsonicAuthInterceptor] can also authenticate requests that do not
 * come from [SubsonicApi] (Media3 streams, covers).
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
