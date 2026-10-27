package keinabersmart.kleinaberlaut.core.data.source

import android.util.Log
import keinabersmart.kleinaberlaut.core.data.security.SecretStore
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import keinabersmart.kleinaberlaut.core.datasource.local.LocalDataSource
import keinabersmart.kleinaberlaut.core.datasource.subsonic.SubsonicDataSourceFactory
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicCredentials
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicServer
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicServerRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Zugriff auf alle Musikquellen per `sourceId`. */
interface MusicDataSources {
    suspend fun all(): List<MusicDataSource>

    suspend operator fun get(sourceId: String): MusicDataSource?
}

/**
 * Lokale Quelle plus alle eingerichteten Server. Server werden beim ersten Zugriff aus der
 * Datenbank und dem [SecretStore] geladen und zugleich in der [SubsonicServerRegistry] angemeldet,
 * damit Streams und Cover authentifiziert werden.
 */
@Singleton
class DataSourceRegistry @Inject constructor(
    private val localDataSource: LocalDataSource,
    private val sourceDao: SourceDao,
    private val secretStore: SecretStore,
    private val subsonicFactory: SubsonicDataSourceFactory,
    private val serverRegistry: SubsonicServerRegistry,
) : MusicDataSources {

    private val mutex = Mutex()

    @Volatile
    private var servers: Map<String, MusicDataSource>? = null

    override suspend fun all(): List<MusicDataSource> = listOf(localDataSource) + loadedServers().values

    override suspend fun get(sourceId: String): MusicDataSource? =
        if (sourceId == localDataSource.sourceId) localDataSource else loadedServers()[sourceId]

    suspend fun servers(): List<MusicDataSource> = loadedServers().values.toList()

    /** Nach dem Hinzufügen oder Entfernen eines Servers aufrufen. */
    suspend fun reload() {
        mutex.withLock { servers = load() }
    }

    private suspend fun loadedServers(): Map<String, MusicDataSource> =
        servers ?: mutex.withLock { servers ?: load().also { servers = it } }

    private suspend fun load(): Map<String, MusicDataSource> {
        val loaded = sourceDao.getByType(SourceType.SUBSONIC).mapNotNull { entity -> toServer(entity)?.let { it to entity.name } }
        serverRegistry.replaceAll(loaded.map { it.first })
        return loaded.associate { (server, name) -> server.sourceId to subsonicFactory.create(server, name) }
    }

    private suspend fun toServer(entity: SourceEntity): SubsonicServer? {
        val baseUrl = entity.baseUrl?.toHttpUrlOrNull()
        val username = entity.username
        val password = secretStore.get(passwordKey(entity.sourceId))
        if (baseUrl == null || username == null || password == null) {
            Log.w(TAG, "Server ${entity.name} ist unvollständig eingerichtet und wird übersprungen")
            return null
        }
        return SubsonicServer(entity.sourceId, baseUrl, SubsonicCredentials.Password(username, password))
    }

    companion object {
        private const val TAG = "DataSourceRegistry"

        fun passwordKey(sourceId: String) = "server:$sourceId:password"
    }
}
