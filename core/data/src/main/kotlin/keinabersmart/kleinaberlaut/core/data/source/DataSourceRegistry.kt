package keinabersmart.kleinaberlaut.core.data.source

import android.util.Log
import keinabersmart.kleinaberlaut.core.data.security.SecretStore
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySettings
import keinabersmart.kleinaberlaut.core.database.dao.SourceDao
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import keinabersmart.kleinaberlaut.core.datasource.local.LocalDataSource
import keinabersmart.kleinaberlaut.core.datasource.subsonic.SubsonicDataSourceFactory
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicCredentials
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicServer
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicServerRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * Lokale Quelle (wenn eingeschaltet, [LibrarySettings.useLocalMedia]) plus alle eingerichteten Server. Server werden beim ersten Zugriff aus der
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
    private val librarySettings: LibrarySettings,
) : MusicDataSources {

    private val mutex = Mutex()

    @Volatile
    private var servers: Map<String, MusicDataSource>? = null

    private val unusable = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Server, die sich nicht nutzen lassen, weil das gespeicherte Passwort fehlt oder sich nicht mehr
     * entschlüsseln lässt (z. B. nach Wiederherstellung auf einem anderen Gerät). Sie werden beim Abgleich
     * übersprungen; der Nutzer muss sie neu einrichten.
     */
    val unusableServers: StateFlow<Set<String>> = unusable.asStateFlow()

    /** Ausgeschaltete Server; sie bleiben über [get] erreichbar (Cover, Downloads), werden aber nicht abgeglichen. */
    @Volatile
    private var disabled: Set<String> = emptySet()

    override suspend fun all(): List<MusicDataSource> =
        listOfNotNull(localDataSource.takeIf { librarySettings.useLocalMedia.first() }) + servers()

    override suspend fun get(sourceId: String): MusicDataSource? =
        if (sourceId == localDataSource.sourceId) localDataSource else loadedServers()[sourceId]

    /** Die eingeschalteten Server. */
    suspend fun servers(): List<MusicDataSource> {
        val all = loadedServers()
        return all.filterKeys { it !in disabled }.values.toList()
    }

    /** Nach dem Hinzufügen, Entfernen, Ein- oder Ausschalten eines Servers aufrufen. */
    suspend fun reload() {
        mutex.withLock { servers = load() }
    }

    private suspend fun loadedServers(): Map<String, MusicDataSource> =
        servers ?: mutex.withLock { servers ?: load().also { servers = it } }

    private suspend fun load(): Map<String, MusicDataSource> {
        val entities = sourceDao.getByType(SourceType.SUBSONIC)
        disabled = entities.filterNot { it.enabled }.mapTo(HashSet()) { it.sourceId }
        val loaded = entities.mapNotNull { entity -> toServer(entity)?.let { it to entity.name } }
        unusable.value = entities.mapTo(HashSet()) { it.sourceId } - loaded.mapTo(HashSet()) { it.first.sourceId }
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
        return SubsonicServer(
            sourceId = entity.sourceId,
            baseUrl = baseUrl,
            credentials = SubsonicCredentials.Password(username, password),
            // JSON-Liste der OpenSubsonic-Extensions, siehe ServerRepository.addServer.
            supportsFormPost = entity.serverCapabilities?.contains("\"formPost\"") == true,
        )
    }

    companion object {
        private const val TAG = "DataSourceRegistry"

        fun passwordKey(sourceId: String) = "server:$sourceId:password"
    }
}
