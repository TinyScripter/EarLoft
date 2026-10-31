package keinabersmart.kleinaberlaut.core.data.spoken

import android.util.Log
import keinabersmart.kleinaberlaut.core.data.security.SecretStore
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.entity.SpokenServerEntity
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsException
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsServer
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsServerRegistry
import keinabersmart.kleinaberlaut.core.model.SpokenServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AddServerResult {
    data class Added(val serverId: String) : AddServerResult
    data object InvalidUrl : AddServerResult
    data object Unreachable : AddServerResult
    data object AuthFailed : AddServerResult
    data object NoBookLibrary : AddServerResult
}

/** Eingerichtete Audiobookshelf-Server (Konzept 12.1); der API-Key liegt im [SecretStore]. */
@Singleton
class AudiobookshelfServers @Inject constructor(
    private val bookDao: BookDao,
    private val secretStore: SecretStore,
    private val api: AbsApi,
    private val registry: AbsServerRegistry,
) {
    val servers: Flow<List<SpokenServer>> = bookDao.observeServers().map { list ->
        list.map { SpokenServer(it.serverId, it.name, it.baseUrl, it.lastFullSyncAt) }
    }

    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    /** Alle Server, deren Key sich lesen lässt. */
    suspend fun all(): List<AbsServer> {
        ensureLoaded()
        return bookDao.servers().mapNotNull { registry[it.serverId] }
    }

    suspend fun server(serverId: String): AbsServer? {
        ensureLoaded()
        return registry[serverId]
    }

    suspend fun add(name: String, baseUrlInput: String, apiKey: String): AddServerResult {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return AddServerResult.InvalidUrl
        val key = apiKey.trim()
        if (key.isEmpty()) return AddServerResult.AuthFailed
        val candidate = AbsServer(UUID.randomUUID().toString(), baseUrl, key)
        val library = try {
            api.ping(candidate)
            api.me(candidate)
            api.libraries(candidate).firstOrNull { it.mediaType == "book" }
        } catch (e: AbsException) {
            Log.w(TAG, "Server $baseUrl abgelehnt", e)
            return if (e.isAuthError) AddServerResult.AuthFailed else AddServerResult.Unreachable
        } catch (e: IOException) {
            Log.w(TAG, "Server $baseUrl nicht erreichbar", e)
            return AddServerResult.Unreachable
        } ?: return AddServerResult.NoBookLibrary

        secretStore.put(tokenKey(candidate.serverId), key)
        bookDao.upsertServer(
            SpokenServerEntity(
                serverId = candidate.serverId,
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                libraryId = library.id,
            ),
        )
        registry.register(candidate)
        return AddServerResult.Added(candidate.serverId)
    }

    /** Entfernt den Server samt Büchern, Kapiteln und Fortschritt. Der Fortschritt auf dem Server bleibt. */
    suspend fun remove(serverId: String) {
        bookDao.deleteServer(serverId)
        secretStore.remove(tokenKey(serverId))
        registry.unregister(serverId)
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val loadedServers = bookDao.servers().mapNotNull { entity ->
                val key = secretStore.get(tokenKey(entity.serverId))
                val baseUrl = AbsServer.normalizeBaseUrl(entity.baseUrl)
                if (key == null || baseUrl == null) {
                    Log.w(TAG, "Server ${entity.name} ist unvollständig eingerichtet und wird übersprungen")
                    null
                } else {
                    AbsServer(entity.serverId, baseUrl, key)
                }
            }
            registry.replaceAll(loadedServers)
            loaded = true
        }
    }

    private companion object {
        const val TAG = "AudiobookshelfServers"

        fun tokenKey(serverId: String) = "abs:$serverId:token"
    }
}
