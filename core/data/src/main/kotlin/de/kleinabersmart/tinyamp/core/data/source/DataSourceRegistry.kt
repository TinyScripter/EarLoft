package de.kleinabersmart.tinyamp.core.data.source

import android.util.Log
import de.kleinabersmart.tinyamp.core.data.security.SecretStore
import de.kleinabersmart.tinyamp.core.data.sync.LibrarySettings
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.datasource.MusicDataSource
import de.kleinabersmart.tinyamp.core.datasource.local.LocalDataSource
import de.kleinabersmart.tinyamp.core.datasource.subsonic.SubsonicDataSourceFactory
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicServer
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicServerRegistry
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

    /** Eingeschaltete Server ohne gespeichertes Passwort (nach dem Wiederherstellen auf einem neuen Gerät). */
    suspend fun withoutPassword(): List<String> = sourceDao.getByType(SourceType.SUBSONIC)
        .filter { it.enabled && secretStore.get(passwordKey(it.sourceId)) == null }
        .map { it.sourceId }

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
