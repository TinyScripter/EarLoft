package de.kleinabersmart.earloft.core.data.source

import android.util.Log
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.jellyfin.JellyfinDataSourceFactory
import de.kleinabersmart.earloft.core.datasource.local.LocalDataSource
import de.kleinabersmart.earloft.core.datasource.subsonic.SubsonicDataSourceFactory
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinFlavor
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServer
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServerRegistry
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServer
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServerRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Access to all music sources by `sourceId`. */
interface MusicDataSources {
    suspend fun all(): List<MusicDataSource>

    suspend operator fun get(sourceId: String): MusicDataSource?
}

/**
 * Local source (if enabled, [LibrarySettings.useLocalMedia]) plus all configured servers. Servers are loaded on first access from the
 * database and the [SecretStore] and at the same time registered in the [SubsonicServerRegistry] or [JellyfinServerRegistry],
 * so streams and covers are authenticated.
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
     * Servers that can't be used because the stored password is missing or can no longer be
     * decrypted (e.g. after restoring on another device). They are skipped during sync;
     * the user has to set them up again.
     */
    val unusableServers: StateFlow<Set<String>> = unusable.asStateFlow()

    /** Disabled servers; they remain reachable via [get] (covers, downloads), but are not synced. */
    @Volatile
    private var disabled: Set<String> = emptySet()

    override suspend fun all(): List<MusicDataSource> =
        listOfNotNull(localDataSource.takeIf { librarySettings.useLocalMedia.first() }) + servers()

    override suspend fun get(sourceId: String): MusicDataSource? =
        if (sourceId == localDataSource.sourceId) localDataSource else loadedServers()[sourceId]

    /** The enabled servers. */
    suspend fun servers(): List<MusicDataSource> {
        val all = loadedServers()
        return all.filterKeys { it !in disabled }.values.toList()
    }

    /** Enabled servers without a stored password or token (after restoring on a new device). */
    suspend fun withoutPassword(): List<String> = sourceDao.getServers()
        .filter { it.enabled && secretStore.get(passwordKey(it.sourceId)) == null }
        .map { it.sourceId }

    /** Call after adding, removing, enabling or disabling a server. */
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
                SourceType.JELLYFIN, SourceType.EMBY ->
                    toJellyfinServer(entity)?.also(jellyfin::add)?.let { jellyfinFactory.create(it, entity.name) }
                SourceType.LOCAL, SourceType.PHONE -> null
            }
        }
        unusable.value = entities.mapTo(HashSet()) { it.sourceId } - loaded.mapTo(HashSet()) { it.sourceId }
        serverRegistry.replaceAll(subsonic)
        jellyfinRegistry.replaceAll(jellyfin)
        return loaded.associateBy { it.sourceId }
    }

    private suspend fun toJellyfinServer(entity: SourceEntity): JellyfinServer? {
        val flavor = JellyfinFlavor.of(entity.type) ?: return null
        val baseUrl = entity.baseUrl?.toHttpUrlOrNull()
        val secret = secretStore.get(passwordKey(entity.sourceId))?.let(JellyfinSecret::decode)
        if (baseUrl == null || secret == null) {
            Log.w(TAG, "Server ${entity.name} is incompletely set up and is skipped")
            return null
        }
        return JellyfinServer(entity.sourceId, baseUrl, secret.userId, secret.token, flavor, jellyfinDeviceId(entity.sourceId))
    }

    /** The device this installation is logged in as with Jellyfin or Emby (see [deviceIdKey]). */
    suspend fun jellyfinDeviceId(sourceId: String): String =
        secretStore.get(deviceIdKey(sourceId)) ?: JellyfinServer.legacyDeviceId(sourceId)

    private suspend fun toSubsonicServer(entity: SourceEntity): SubsonicServer? {
        val baseUrl = entity.baseUrl?.toHttpUrlOrNull()
        val username = entity.username
        val password = secretStore.get(passwordKey(entity.sourceId))
        if (baseUrl == null || username == null || password == null) {
            Log.w(TAG, "Server ${entity.name} is incompletely set up and is skipped")
            return null
        }
        return SubsonicServer(
            sourceId = entity.sourceId,
            baseUrl = baseUrl,
            credentials = SubsonicCredentials.Password(username, password),
            // JSON list of OpenSubsonic extensions, see ServerRepository.addServer.
            supportsFormPost = entity.serverCapabilities?.contains("\"formPost\"") == true,
        )
    }

    companion object {
        private const val TAG = "DataSourceRegistry"

        /** A server's secret: with Subsonic the password, with Jellyfin and Emby [JellyfinSecret] (user ID and token). */
        fun passwordKey(sourceId: String) = "server:$sourceId:password"

        /**
         * Jellyfin and Emby: the device of the login. Like the secret, not part of the backup, so that a second device with
         * the same backup logs in as its own. Missing for logins from before ([JellyfinServer.legacyDeviceId]).
         */
        fun deviceIdKey(sourceId: String) = "server:$sourceId:device"
    }
}
