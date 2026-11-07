package de.kleinabersmart.tinyamp.core.data.source

import android.util.Log
import de.kleinabersmart.tinyamp.core.data.security.SecretStore
import de.kleinabersmart.tinyamp.core.data.sync.LibrarySettings
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.datasource.MusicDataSource
import de.kleinabersmart.tinyamp.core.datasource.jellyfin.JellyfinDataSourceFactory
import de.kleinabersmart.tinyamp.core.datasource.local.LocalDataSource
import de.kleinabersmart.tinyamp.core.datasource.subsonic.SubsonicDataSourceFactory
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.network.jellyfin.JellyfinServer
import de.kleinabersmart.tinyamp.core.network.jellyfin.JellyfinServerRegistry
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
 * Datenbank und dem [SecretStore] geladen und zugleich in der [SubsonicServerRegistry] bzw. [JellyfinServerRegistry]
 * angemeldet, damit Streams und Cover authentifiziert werden.
 */
@Singleton
class DataSourceRegistry @Inject constructor(
    private val localDataSource: LocalDataSource,
    private val sourceDao: SourceDao,
    private val secretStore: SecretStore,
    private val subsonicFactory: SubsonicDataSourceFactory,
    private val serverRegistry: SubsonicServerRegistry,
    private val jellyfinFactory: JellyfinDataSourceFactory,
    private val jellyfinRegistry: JellyfinServerRegistry,
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

    /** Eingeschaltete Server ohne gespeichertes Passwort bzw. Token (nach dem Wiederherstellen auf einem neuen Gerät). */
    suspend fun withoutPassword(): List<String> = sourceDao.getServers()
        .filter { it.enabled && secretStore.get(passwordKey(it.sourceId)) == null }
        .map { it.sourceId }

    /** Nach dem Hinzufügen, Entfernen, Ein- oder Ausschalten eines Servers aufrufen. */
    suspend fun reload() {
        mutex.withLock { servers = load() }
    }

    private suspend fun loadedServers(): Map<String, MusicDataSource> =
        servers ?: mutex.withLock { servers ?: load().also { servers = it } }

    private suspend fun load(): Map<String, MusicDataSource> {
        val entities = sourceDao.getServers()
        disabled = entities.filterNot { it.enabled }.mapTo(HashSet()) { it.sourceId }
        val subsonic = mutableListOf<SubsonicServer>()
        val jellyfin = mutableListOf<JellyfinServer>()
        val loaded = entities.mapNotNull { entity ->
            when (entity.type) {
                SourceType.SUBSONIC -> toSubsonicServer(entity)?.also(subsonic::add)?.let { subsonicFactory.create(it, entity.name) }
                SourceType.JELLYFIN -> toJellyfinServer(entity)?.also(jellyfin::add)?.let { jellyfinFactory.create(it, entity.name) }
                SourceType.LOCAL, SourceType.PHONE -> null
            }
        }
        unusable.value = entities.mapTo(HashSet()) { it.sourceId } - loaded.mapTo(HashSet()) { it.sourceId }
        serverRegistry.replaceAll(subsonic)
        jellyfinRegistry.replaceAll(jellyfin)
        return loaded.associateBy { it.sourceId }
    }

    private suspend fun toJellyfinServer(entity: SourceEntity): JellyfinServer? {
        val baseUrl = entity.baseUrl?.toHttpUrlOrNull()
        val secret = secretStore.get(passwordKey(entity.sourceId))?.let(JellyfinSecret::decode)
        if (baseUrl == null || secret == null) {
            Log.w(TAG, "Server ${entity.name} ist unvollständig eingerichtet und wird übersprungen")
            return null
        }
        return JellyfinServer(entity.sourceId, baseUrl, secret.userId, secret.token)
    }

    private suspend fun toSubsonicServer(entity: SourceEntity): SubsonicServer? {
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

        /** Das Secret eines Servers: bei Subsonic das Passwort, bei Jellyfin [JellyfinSecret] (Benutzer-ID und Token). */
        fun passwordKey(sourceId: String) = "server:$sourceId:password"
    }
}
