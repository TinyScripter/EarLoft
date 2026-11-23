package de.kleinabersmart.earloft.core.data.repository

import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.source.JellyfinSecret
import de.kleinabersmart.earloft.core.data.sync.LibraryChangeListener
import de.kleinabersmart.earloft.core.data.sync.LibraryRefresher
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.ServerInfo
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.ServerApiException
import de.kleinabersmart.earloft.core.network.UnexpectedResponseException
import de.kleinabersmart.earloft.core.network.keepsCredentialsOf
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinFlavor
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServer
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicApi
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServer
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result of a connection test. */
sealed interface ConnectionResult {
    /** [sourceId]: only set after [ServerRepository.addServer] – the ID of the new server. */
    data class Success(val serverName: String, val serverVersion: String?, val sourceId: String? = null) : ConnectionResult
    data object InvalidUrl : ConnectionResult
    data object WrongCredentials : ConnectionResult

    /** Editing: the new address is on another host, so the stored password or sign-in doesn't go there (review J28). */
    data object CredentialsNeeded : ConnectionResult

    /** No server of the chosen type responds at this address. */
    data object UnexpectedServer : ConnectionResult
    data class Unreachable(val reason: String?) : ConnectionResult
    data class ServerError(val code: Int, val message: String?) : ConnectionResult

    /** A server of this type with this address and user name exists already ([sourceId]); nothing was created (review H23). */
    data class AlreadySetUp(val sourceId: String) : ConnectionResult
}

/** A server for editing; password or token never leave the repository. */
data class EditableServer(val info: ServerInfo, val hasPassword: Boolean)

/** Setting up, checking, editing and removing music servers (Navidrome/Subsonic, Jellyfin, Emby). */
@Singleton
class ServerRepository @Inject constructor(
    private val database: EarLoftDatabase,
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val playlistDao: PlaylistDao,
    private val secretStore: SecretStore,
    private val registry: DataSourceRegistry,
    private val api: SubsonicApi,
    private val jellyfinApi: JellyfinApi,
    private val refresher: LibraryRefresher,
    private val changeListeners: Set<@JvmSuppressWildcards LibraryChangeListener>,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    fun servers(): Flow<List<ServerInfo>> =
        sourceDao.observeServers().map { sources -> sources.map { it.toServerInfo() } }

    /** @return `null` if the server doesn't exist (anymore). */
    suspend fun editableServer(sourceId: String): EditableServer? {
        val entity = sourceDao.get(sourceId)?.takeIf { it.type.isServer } ?: return null
        val hasSecret = when (entity.type) {
            SourceType.JELLYFIN, SourceType.EMBY -> storedJellyfinSecret(sourceId) != null
            else -> storedPassword(sourceId) != null
        }
        return EditableServer(entity.toServerInfo(), hasPassword = hasSecret)
    }

    /**
     * Checks the connection and only creates the server on success. The first sync starts
     * immediately afterwards. @return the test result.
     */
    suspend fun addServer(type: SourceType, name: String, url: String, username: String, password: String): ConnectionResult {
        existingServer(type, url, username)?.let { return ConnectionResult.AlreadySetUp(it) }
        return when (val flavor = JellyfinFlavor.of(type)) {
            null -> addSubsonicServer(name, url, username, password)
            else -> addJellyfinServer(flavor, name, url, username, password)
        }
    }

    /**
     * The server of [type] at [url] for [username], if set up already (address as stored, user name without regard to case,
     * as the servers sign in). Another user on the same server is a server of its own.
     */
    private suspend fun existingServer(type: SourceType, url: String, username: String): String? {
        val baseUrl = when (JellyfinFlavor.of(type)) {
            null -> SubsonicServer.normalizeBaseUrl(url)
            else -> JellyfinServer.normalizeBaseUrl(url)
        }?.toString() ?: return null
        val user = username.trim()
        return sourceDao.observeServers().first().firstOrNull {
            it.type == type && it.baseUrl.equals(baseUrl, ignoreCase = true) && it.username.orEmpty().equals(user, ignoreCase = true)
        }?.sourceId
    }

    /**
     * Changes name, address, user or password of a server, only if the connection works with the
     * new details. The `sourceId` stays, so songs, downloads, favorites and playlists
     * are kept (and the watch doesn't have to reload anything). [newPassword] `null` = unchanged.
     *
     * A full sync follows: if the address now points to a different library, the
     * mark-and-sweep removes the old songs.
     */
    suspend fun updateServer(
        sourceId: String,
        name: String,
        url: String,
        username: String,
        newPassword: String?,
    ): ConnectionResult {
        val existing = sourceDao.get(sourceId) ?: return ConnectionResult.InvalidUrl
        return when (val flavor = JellyfinFlavor.of(existing.type)) {
            null -> updateSubsonicServer(existing, name, url, username, newPassword)
            else -> updateJellyfinServer(flavor, existing, name, url, username, newPassword)
        }
    }

    // --- Subsonic ---

    private suspend fun testSubsonic(url: String, username: String, password: String): ConnectionResult {
        val baseUrl = SubsonicServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val server = SubsonicServer(TEST_SOURCE_ID, baseUrl, SubsonicCredentials.Password(username, password))
        return try {
            val pong = api.ping(server)
            ConnectionResult.Success(
                serverName = pong.type ?: baseUrl.host,
                serverVersion = pong.serverVersion ?: pong.version,
            )
        } catch (e: IOException) {
            connectionFailure(e)
        }
    }

    private suspend fun addSubsonicServer(name: String, url: String, username: String, password: String): ConnectionResult {
        val result = testSubsonic(url, username, password)
        if (result !is ConnectionResult.Success) return result

        val baseUrl = SubsonicServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val sourceId = UUID.randomUUID().toString()
        val server = SubsonicServer(sourceId, baseUrl, SubsonicCredentials.Password(username, password))
        // Before storing anything: a connection that breaks off right here is a failed test, not a crash (review G43).
        val capabilities = capabilitiesOrFailure(server) ?: return ConnectionResult.Unreachable(null)

        secretStore.put(DataSourceRegistry.passwordKey(sourceId), password)
        sourceDao.upsert(
            SourceEntity(
                sourceId = sourceId,
                type = SourceType.SUBSONIC,
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                username = username,
                serverCapabilities = capabilities,
            ),
        )
        registry.reload()
        refresher.refreshSource(sourceId)
        return result.copy(sourceId = sourceId)
    }

    private suspend fun updateSubsonicServer(
        existing: SourceEntity,
        name: String,
        url: String,
        username: String,
        newPassword: String?,
    ): ConnectionResult {
        val sourceId = existing.sourceId
        val baseUrl = SubsonicServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        if (newPassword == null && !baseUrl.keepsCredentialsOf(existing.baseUrl)) return ConnectionResult.CredentialsNeeded
        val password = newPassword ?: storedPassword(sourceId) ?: return ConnectionResult.WrongCredentials
        val result = testSubsonic(url, username, password)
        if (result !is ConnectionResult.Success) return result

        val server = SubsonicServer(sourceId, baseUrl, SubsonicCredentials.Password(username, password))
        val capabilities = capabilitiesOrFailure(server) ?: return ConnectionResult.Unreachable(null)
        if (newPassword != null) secretStore.put(DataSourceRegistry.passwordKey(sourceId), newPassword)
        // Sync generation and timestamp stay, otherwise the mark-and-sweep would consider everything new.
        sourceDao.upsert(
            existing.copy(
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                username = username,
                serverCapabilities = capabilities,
            ),
        )
        registry.reload()
        refresher.refreshSource(sourceId)
        return result
    }

    // --- Jellyfin and Emby (concept 14): only user ID and token are stored, never the password ---

    /**
     * Logs this device in for the source [sourceId]; with Jellyfin, an earlier token of the same device becomes invalid.
     * The device stays the same per installation; a login from before gets its own next time (R28).
     */
    private suspend fun loginJellyfin(
        flavor: JellyfinFlavor,
        sourceId: String,
        baseUrl: HttpUrl,
        username: String,
        password: String,
    ): Pair<ConnectionResult, JellyfinSecret?> =
        try {
            val info = jellyfinApi.publicInfo(baseUrl, flavor)
            val deviceKey = DataSourceRegistry.deviceIdKey(sourceId)
            val deviceId = secretStore.get(deviceKey) ?: JellyfinServer.newDeviceId()
            val auth = jellyfinApi.authenticate(baseUrl, deviceId, username, password)
            secretStore.put(deviceKey, deviceId)
            ConnectionResult.Success(info.productName ?: flavor.productName, info.version) to JellyfinSecret(auth.user.id, auth.accessToken)
        } catch (e: IOException) {
            connectionFailure(e) to null
        }

    private suspend fun addJellyfinServer(
        flavor: JellyfinFlavor,
        name: String,
        url: String,
        username: String,
        password: String,
    ): ConnectionResult {
        val baseUrl = JellyfinServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val sourceId = UUID.randomUUID().toString()
        val (result, secret) = loginJellyfin(flavor, sourceId, baseUrl, username, password)
        if (result !is ConnectionResult.Success || secret == null) return result

        secretStore.put(DataSourceRegistry.passwordKey(sourceId), secret.encode())
        sourceDao.upsert(
            SourceEntity(
                sourceId = sourceId,
                type = flavor.sourceType,
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                username = username,
            ),
        )
        registry.reload()
        refresher.refreshSource(sourceId)
        return result.copy(sourceId = sourceId)
    }

    /** Without a new password the token stays, as long as it belongs to the account [username] at the (new) address. */
    private suspend fun updateJellyfinServer(
        flavor: JellyfinFlavor,
        existing: SourceEntity,
        name: String,
        url: String,
        username: String,
        newPassword: String?,
    ): ConnectionResult {
        val sourceId = existing.sourceId
        val baseUrl = JellyfinServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val result = if (newPassword != null) {
            val previous = jellyfinLogin(existing)
            val (result, secret) = loginJellyfin(flavor, sourceId, baseUrl, username, newPassword)
            if (result !is ConnectionResult.Success || secret == null) return result
            secretStore.put(DataSourceRegistry.passwordKey(sourceId), secret.encode())
            // Otherwise the old token would stay on the server: always with Emby, with Jellyfin if it comes from a login
            // under the earlier device.
            if (previous != null && previous.token != secret.token) {
                applicationScope.launch { logoutJellyfin(previous) }
            }
            result
        } else {
            if (!baseUrl.keepsCredentialsOf(existing.baseUrl)) return ConnectionResult.CredentialsNeeded
            val stored = storedJellyfinSecret(sourceId) ?: return ConnectionResult.WrongCredentials
            try {
                val info = jellyfinApi.publicInfo(baseUrl, flavor)
                val me = jellyfinApi.me(JellyfinServer(sourceId, baseUrl, stored.userId, stored.token, flavor, registry.jellyfinDeviceId(sourceId)))
                if (!me.name.equals(username.trim(), ignoreCase = true)) return ConnectionResult.WrongCredentials
                ConnectionResult.Success(info.productName ?: flavor.productName, info.version)
            } catch (e: IOException) {
                return connectionFailure(e)
            }
        }
        sourceDao.upsert(
            existing.copy(
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                username = username,
            ),
        )
        registry.reload()
        refresher.refreshSource(sourceId)
        return result
    }

    /** The login that [logoutJellyfin] releases; `null` without a valid address or token. */
    private suspend fun jellyfinLogin(entity: SourceEntity): JellyfinServer? {
        val flavor = JellyfinFlavor.of(entity.type) ?: return null
        val baseUrl = entity.baseUrl?.toHttpUrlOrNull() ?: return null
        val secret = storedJellyfinSecret(entity.sourceId) ?: return null
        return JellyfinServer(entity.sourceId, baseUrl, secret.userId, secret.token, flavor, registry.jellyfinDeviceId(entity.sourceId))
    }

    /** Releases the token; offline or without a response it simply stays on the server. */
    private suspend fun logoutJellyfin(server: JellyfinServer) {
        try {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { jellyfinApi.logout(server) }
        } catch (e: IOException) {
            Log.w(TAG, "Logout at ${server.baseUrl} failed: ${e.message}")
        }
    }

    private fun connectionFailure(e: IOException): ConnectionResult = when (e) {
        is ServerApiException ->
            if (e.isAuthError) ConnectionResult.WrongCredentials else ConnectionResult.ServerError(e.code, e.serverMessage)
        is UnexpectedResponseException -> ConnectionResult.UnexpectedServer
        is HttpStatusException -> when {
            e.isAuthError -> ConnectionResult.WrongCredentials
            e.status == 404 -> ConnectionResult.UnexpectedServer
            else -> ConnectionResult.Unreachable(e.message)
        }
        else -> ConnectionResult.Unreachable(e.message)
    }

    /**
     * Turns the server on or off. When off, everything is kept (songs, downloads, favorites,
     * playlists); only display, playback sources and sync are paused. Turning it on syncs immediately.
     */
    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        sourceDao.setEnabled(sourceId, enabled)
        registry.reload()
        if (enabled) refresher.refreshSource(sourceId)
    }

    /**
     * Removes the server along with all songs only it provided. Runs to completion even if the caller (e.g. the
     * settings) is gone in the meantime; logging out of Jellyfin and Emby follows in the background afterwards, so that a server without
     * response doesn't hold up the removal.
     */
    suspend fun removeServer(sourceId: String) = withContext(NonCancellable) {
        val login = sourceDao.get(sourceId)?.let { jellyfinLogin(it) }
        database.withTransaction {
            sourceDao.delete(sourceId)
            // The server's playlists remain there; locally they disappear with it.
            playlistDao.deleteBoundTo(sourceId)
            libraryDao.cleanupOrphans()
        }
        secretStore.remove(DataSourceRegistry.passwordKey(sourceId))
        secretStore.remove(DataSourceRegistry.deviceIdKey(sourceId))
        registry.reload()
        changeListeners.forEach { it.onSongSourcesRemoved() }
        if (login != null) applicationScope.launch { logoutJellyfin(login) }
    }

    fun syncNow(sourceId: String) = refresher.refreshSource(sourceId)

    private suspend fun storedPassword(sourceId: String): String? =
        secretStore.get(DataSourceRegistry.passwordKey(sourceId))

    private suspend fun storedJellyfinSecret(sourceId: String): JellyfinSecret? =
        storedPassword(sourceId)?.let(JellyfinSecret::decode)

    /** OpenSubsonic extensions as a JSON list, see [DataSourceRegistry]. */
    private suspend fun capabilities(server: SubsonicServer): String =
        api.openSubsonicExtensions(server).joinToString(",", prefix = "[", postfix = "]") { "\"${it.name}\"" }

    /** [capabilities]; `null` if the connection broke off. */
    private suspend fun capabilitiesOrFailure(server: SubsonicServer): String? =
        try {
            capabilities(server)
        } catch (e: IOException) {
            Log.w(TAG, "Extensions of ${server.baseUrl.host} not readable", e)
            null
        }

    private fun SourceEntity.toServerInfo() = ServerInfo(
        sourceId = sourceId,
        name = name,
        baseUrl = baseUrl.orEmpty(),
        username = username,
        lastFullSyncAt = lastFullSyncAt,
        enabled = enabled,
        type = type,
    )

    private companion object {
        const val TAG = "ServerRepository"
        const val TEST_SOURCE_ID = "connection-test"
        const val LOGOUT_TIMEOUT_MS = 5_000L
    }
}
