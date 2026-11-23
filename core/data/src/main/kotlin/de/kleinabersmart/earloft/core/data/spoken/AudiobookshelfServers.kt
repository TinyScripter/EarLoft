package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.SpokenLibraryEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsLoginOptions
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsOpenIdLogin
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import de.kleinabersmart.earloft.core.network.keepsCredentialsOf
import de.kleinabersmart.earloft.core.model.SpokenLibrary
import de.kleinabersmart.earloft.core.model.SpokenServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface AddServerResult {
    data class Added(val serverId: String) : AddServerResult
    data object InvalidUrl : AddServerResult
    data object Unreachable : AddServerResult
    data object AuthFailed : AddServerResult
    /** Editing: the new address is on another host, so the stored key or sign-in doesn't go there (review J28). */
    data object CredentialsNeeded : AddServerResult
    /** The server did not complete the OpenID sign-in (rejected, expired, foreign redirect). */
    data object OpenIdFailed : AddServerResult
    data object NoBookLibrary : AddServerResult
}

/** Start of an OpenID sign-in ([AudiobookshelfServers.beginOpenId]). */
sealed interface BeginOpenIdResult {
    data class Started(val login: AbsOpenIdLogin) : BeginOpenIdResult
    data object InvalidUrl : BeginOpenIdResult
    data object Unreachable : BeginOpenIdResult
    /** Audiobookshelf does not allow [redirectUri]: it belongs in "Allowed Mobile Redirect URIs". */
    data class RedirectNotAllowed(val redirectUri: String) : BeginOpenIdResult
}

/** A server for editing; API key and tokens never leave the [SecretStore]. */
data class EditableAbsServer(val name: String, val baseUrl: String, val hasKey: Boolean, val openId: Boolean = false)

/**
 * A server as the phone hands it to the watch (without key, Wear concept 16.2). [credentialsStamp] changes with the
 * key ([CredentialsStamps]); `null` if the phone has none.
 */
data class MirroredAbsServer(val serverId: String, val name: String, val baseUrl: String, val credentialsStamp: String? = null)

/**
 * Configured Audiobookshelf servers (concept 12.1). Signed in via API key or via OpenID (12.11); the API key or access and
 * refresh tokens live in the [SecretStore] ([AbsSecrets]), renewed by [AbsTokenRenewal].
 */
@Singleton
class AudiobookshelfServers @Inject constructor(
    private val bookDao: BookDao,
    private val secretStore: SecretStore,
    private val api: AbsApi,
    private val authApi: AbsAuthApi,
    private val renewal: AbsTokenRenewal,
    private val registry: AbsServerRegistry,
    private val stamps: CredentialsStamps,
) {
    val servers: Flow<List<SpokenServer>> = combine(bookDao.observeServers(), bookDao.observeLibraries()) { list, libraries ->
        val byServer = libraries.groupBy { it.serverId }
        list.map { server ->
            val hidden = server.hiddenLibraryIds
            SpokenServer(
                server.serverId, server.name, server.baseUrl, server.lastFullSyncAt, server.enabled, server.openId,
                libraries = byServer[server.serverId].orEmpty().map { SpokenLibrary(it.serverId, it.libraryId, it.name, it.libraryId !in hidden) },
            )
        }
    }

    /**
     * Deselects or reselects a book library (`books_improvements.md` M3); its books are hidden or shown immediately.
     * Deselected, they stay stored along with downloads, progress and bookmarks, but are not synced (like with a
     * disabled server). The last visible one stays: `false` if it was about to be deselected.
     */
    suspend fun setLibraryShown(serverId: String, libraryId: String, shown: Boolean): Boolean = librariesMutex.withLock {
        // Read and write under one lock: otherwise quick clicks on several libraries would overwrite each other.
        val entity = bookDao.server(serverId) ?: return@withLock false
        val hidden = if (shown) entity.hiddenLibraryIds - libraryId else entity.hiddenLibraryIds + libraryId
        val known = bookDao.libraries(serverId).map { it.libraryId }
        if (known.isNotEmpty() && known.all { it in hidden }) return@withLock false
        bookDao.setHiddenLibraries(serverId, hidden.sorted().joinToString(","))
        true
    }

    /**
     * Phone: the enabled servers for the watch. Disabled ones are missing; their books are hidden on the phone and drop off
     * the watch. A new key also rewrites the server ([connect]), which brings a new stamp. With OpenID the stamp is tied to
     * the sign-in, not to the access token that renews hourly: the watch fetches that itself once it has expired.
     */
    val mirrored: Flow<List<MirroredAbsServer>> = bookDao.observeServers().map { list ->
        list.filter { it.enabled }.map {
            val secret = if (it.openId) secretStore.get(AbsSecrets.login(it.serverId)) else secretStore.get(tokenKey(it.serverId))
            MirroredAbsServer(it.serverId, it.name, it.baseUrl, stamps.absStamp(it.serverId, secret))
        }
    }

    /**
     * Servers whose API key or sign-in is missing (e.g. after a restore on another device, or the refresh token has
     * expired); the user has to re-enter the key or sign in again when editing. Recomputed when the servers
     * change – saving a key changes them too – and when a sign-in expires.
     */
    val serversWithoutKey: Flow<Set<String>> = combine(bookDao.observeServers(), renewal.expired) { list, _ ->
        list.filter { secretStore.get(tokenKey(it.serverId)) == null }.mapTo(HashSet()) { it.serverId }
    }

    private val mutex = Mutex()
    private val librariesMutex = Mutex()

    @Volatile
    private var loaded = false

    /** All enabled servers whose key can be read. */
    suspend fun all(): List<AbsServer> {
        ensureLoaded()
        return bookDao.servers().filter { it.enabled }.mapNotNull { registry[it.serverId] }
    }

    suspend fun server(serverId: String): AbsServer? {
        ensureLoaded()
        return registry[serverId]
    }

    /** Like [all], but also disabled servers, as with [server]. */
    suspend fun allIncludingDisabled(): List<AbsServer> {
        ensureLoaded()
        return bookDao.servers().mapNotNull { registry[it.serverId] }
    }

    suspend fun add(name: String, baseUrlInput: String, apiKey: String): AddServerResult =
        connect(null, name, baseUrlInput, Credentials.ApiKey(apiKey))

    /**
     * Changes a server's name, address or API key, only if the connection works with the new details.
     * The `serverId` stays so that books, progress and downloads are kept. Empty [apiKey] = the
     * stored key stays. If the address points to a different library, the next full sync cleans up.
     */
    suspend fun update(serverId: String, name: String, baseUrlInput: String, apiKey: String): AddServerResult =
        connect(serverId, name, baseUrlInput, Credentials.ApiKey(apiKey))

    /** Does the server offer OpenID sign-in? `null` if the address is invalid or not an Audiobookshelf server. */
    suspend fun loginOptions(baseUrlInput: String): AbsLoginOptions? {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return null
        return try {
            authApi.loginOptions(baseUrl)
        } catch (e: IOException) {
            Log.d(TAG, "Login methods of $baseUrl not readable", e)
            null
        }
    }

    /** First step of the OpenID sign-in (concept 12.11); after that the user signs in in the browser. */
    suspend fun beginOpenId(baseUrlInput: String, redirectUri: String): BeginOpenIdResult {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return BeginOpenIdResult.InvalidUrl
        return try {
            BeginOpenIdResult.Started(authApi.beginOpenId(baseUrl, redirectUri))
        } catch (e: AbsException) {
            Log.w(TAG, "OpenID at $baseUrl not possible", e)
            if (e.code == 400) BeginOpenIdResult.RedirectNotAllowed(redirectUri) else BeginOpenIdResult.Unreachable
        } catch (e: IOException) {
            Log.w(TAG, "Server $baseUrl unreachable", e)
            BeginOpenIdResult.Unreachable
        }
    }

    /**
     * Completes the OpenID sign-in with the browser's redirect [redirect] and sets up the server ([serverId]
     * `null`) or signs it in again. As with [update], `serverId`, books and progress are kept.
     */
    suspend fun connectOpenId(serverId: String?, name: String, login: AbsOpenIdLogin, redirect: String): AddServerResult {
        val redirectUrl = AbsAuthApi.redirectUrl(redirect) ?: return AddServerResult.OpenIdFailed
        val tokens = try {
            authApi.finishOpenId(login, redirectUrl)
        } catch (e: AbsException) {
            Log.w(TAG, "OpenID at ${login.baseUrl} rejected", e)
            return AddServerResult.OpenIdFailed
        } catch (e: IOException) {
            Log.w(TAG, "Server ${login.baseUrl} unreachable", e)
            return AddServerResult.Unreachable
        }
        return connect(serverId, name, login.baseUrl.toString(), Credentials.OpenId(tokens.accessToken, tokens.refreshToken))
    }

    /** Enables or disables the server; while disabled, books and downloads are kept but hidden. */
    suspend fun setEnabled(serverId: String, enabled: Boolean) {
        bookDao.setServerEnabled(serverId, enabled)
    }

    /**
     * The token for receivers that cannot renew it themselves (cast): with OpenID still valid for at least [minValiditySeconds],
     * renewed beforehand if necessary. `null` if the server is not set up or the token is missing.
     */
    suspend fun freshToken(serverId: String, minValiditySeconds: Long = FRESH_FOR_SECONDS): String? {
        ensureLoaded()
        return renewal.fresh(serverId, minValiditySeconds)
    }

    /** Enabled servers without a stored API key (after restoring on a new device). */
    suspend fun withoutKey(): List<String> {
        val missing = serversWithoutKey.first()
        return bookDao.servers().filter { it.enabled && it.serverId in missing }.map { it.serverId }
    }

    suspend fun editable(serverId: String): EditableAbsServer? {
        val entity = bookDao.server(serverId) ?: return null
        return EditableAbsServer(entity.name, entity.baseUrl, hasKey = secretStore.get(tokenKey(serverId)) != null, openId = entity.openId)
    }

    private sealed interface Credentials {
        /** Empty: the stored API key or stored sign-in stays. */
        data class ApiKey(val key: String) : Credentials
        data class OpenId(val accessToken: String, val refreshToken: String?) : Credentials
    }

    private suspend fun connect(existingId: String?, name: String, baseUrlInput: String, credentials: Credentials): AddServerResult {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return AddServerResult.InvalidUrl
        val existing = existingId?.let { bookDao.server(it) }
        if (existingId != null && existing == null) return AddServerResult.InvalidUrl
        val keep = credentials is Credentials.ApiKey && credentials.key.isBlank()
        if (keep && existing != null && !baseUrl.keepsCredentialsOf(existing.baseUrl)) return AddServerResult.CredentialsNeeded
        // So that an access token that expired while keeping it gets renewed during the check (AbsTokenAuthenticator).
        if (keep) ensureLoaded()
        val key = when (credentials) {
            is Credentials.ApiKey -> credentials.key.trim().ifEmpty { existingId?.let { secretStore.get(tokenKey(it)) }.orEmpty() }
            is Credentials.OpenId -> credentials.accessToken
        }
        if (key.isEmpty()) return AddServerResult.AuthFailed
        val candidate = AbsServer(existingId ?: UUID.randomUUID().toString(), baseUrl, key)
        val libraries = try {
            api.ping(candidate)
            api.me(candidate)
            api.libraries(candidate).filter { it.mediaType == "book" }
        } catch (e: AbsException) {
            Log.w(TAG, "Server $baseUrl rejected", e)
            return if (e.isAuthError) AddServerResult.AuthFailed else AddServerResult.Unreachable
        } catch (e: IOException) {
            Log.w(TAG, "Server $baseUrl unreachable", e)
            return AddServerResult.Unreachable
        }
        val library = libraries.firstOrNull { it.id !in existing?.hiddenLibraryIds.orEmpty() } ?: libraries.firstOrNull()
            ?: return AddServerResult.NoBookLibrary

        val serverId = candidate.serverId
        when (credentials) {
            // When keeping, write nothing: an access token may have been renewed during the check.
            is Credentials.ApiKey -> if (!keep) {
                secretStore.put(tokenKey(serverId), key)
                secretStore.remove(AbsSecrets.refresh(serverId))
                secretStore.remove(AbsSecrets.login(serverId))
            }
            is Credentials.OpenId -> {
                secretStore.put(tokenKey(serverId), key)
                credentials.refreshToken?.let { secretStore.put(AbsSecrets.refresh(serverId), it) }
                    ?: secretStore.remove(AbsSecrets.refresh(serverId))
                secretStore.put(AbsSecrets.login(serverId), UUID.randomUUID().toString())
                renewal.forget(serverId)
            }
        }
        // Sync generation, timestamp and switch stay, otherwise mark-and-sweep would consider everything new.
        bookDao.upsertServer(
            (existing ?: SpokenServerEntity(serverId, name, baseUrl.toString())).copy(
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                libraryId = library.id,
                openId = if (keep) existing?.openId == true else credentials is Credentials.OpenId,
            ),
        )
        // All book libraries; with none deselected, all are included (M3). The selection is in the settings afterwards.
        bookDao.replaceLibraries(serverId, libraries.mapIndexed { index, it -> SpokenLibraryEntity(serverId, it.id, it.name, index) })
        registry.register(candidate.copy(token = secretStore.get(tokenKey(serverId)) ?: key))
        return AddServerResult.Added(serverId)
    }

    /** Removes the server along with books, chapters and progress. The progress on the server stays. */
    suspend fun remove(serverId: String) {
        bookDao.deleteServer(serverId)
        secretStore.remove(tokenKey(serverId))
        secretStore.remove(AbsSecrets.refresh(serverId))
        secretStore.remove(AbsSecrets.login(serverId))
        registry.unregister(serverId)
        renewal.forget(serverId)
    }

    // --- Watch (Wear concept 16.2): servers mirrored without key, the key separately on request ---

    /**
     * Phone: the key for the requesting watch; `null` if [serverId] is not an Audiobookshelf server. With OpenID an
     * access token that is valid for at least another half hour; the watch never gets the refresh token, otherwise both
     * would lock each other out through rotation (concept 12.11).
     */
    suspend fun apiKey(serverId: String): String? {
        val entity = bookDao.server(serverId) ?: return null
        val token = freshToken(serverId) ?: return secretStore.get(tokenKey(serverId))
        if (!entity.openId) return token
        // If the phone's clock is behind, it would consider an expired token valid: the server decides (on 401 the
        // AbsTokenAuthenticator renews, and the registry has the new one afterwards).
        try {
            registry[serverId]?.let { api.me(it) }
        } catch (e: IOException) {
            Log.w(TAG, "Token for the watch not checked", e)
        }
        return registry[serverId]?.token ?: token
    }

    /**
     * Watch: enabled servers whose access token expires in less than [withinSeconds] (OpenID only; API keys do
     * not expire). For that, the watch asks the phone for a fresh one before a download run.
     */
    suspend fun withExpiringToken(withinSeconds: Long): List<String> {
        val deadline = System.currentTimeMillis() / 1000 + withinSeconds
        return all().filter { server -> AbsAuthApi.expiresAt(server.token)?.let { it < deadline } == true }.map { it.serverId }
    }

    /**
     * Watch: adopts the phone's servers. If one is missing there, it disappears along with its books and key; the
     * others keep their books (same `serverId`).
     * @return the servers whose key was discarded because it changed on the phone or belongs to the old host: the
     * watch requests it again right away.
     */
    suspend fun mirror(servers: List<MirroredAbsServer>): Set<String> {
        val existing = bookDao.servers().associateBy { it.serverId }
        val renewed = stamps.changedAbs(servers.associate { it.serverId to it.credentialsStamp })
            .filterTo(HashSet()) { it in existing }
        renewed.forEach { forgetKey(it) }
        servers.forEach { server ->
            val entity = (existing[server.serverId] ?: SpokenServerEntity(server.serverId, server.name, server.baseUrl))
                .copy(name = server.name, baseUrl = server.baseUrl)
            if (entity != existing[server.serverId]) bookDao.upsertServer(entity)
            val previousUrl = existing[server.serverId]?.baseUrl
            if (previousUrl != null && entity.baseUrl != previousUrl) {
                val oldUrl = previousUrl.toHttpUrlOrNull()
                if (oldUrl != null && entity.baseUrl.toHttpUrlOrNull()?.acceptsCredentialsOf(oldUrl) == true) {
                    // New address on the same host: re-register with the stored key.
                    secretStore.get(tokenKey(server.serverId))?.let { key -> register(server.serverId, server.baseUrl, key) }
                } else {
                    // Different host: the key stays with the old one (the watch requests it again from the phone).
                    forgetKey(server.serverId)
                    renewed += server.serverId
                }
            }
        }
        (existing.keys - servers.mapTo(HashSet()) { it.serverId }).forEach {
            remove(it)
            stamps.forgetAbs(it)
        }
        return renewed
    }

    private suspend fun forgetKey(serverId: String) {
        secretStore.remove(tokenKey(serverId))
        registry.unregister(serverId)
    }

    /** Watch: @return `false` if the server is not mirrored (yet). */
    suspend fun storeKey(serverId: String, key: String): Boolean {
        val entity = bookDao.server(serverId) ?: return false
        secretStore.put(tokenKey(serverId), key)
        register(serverId, entity.baseUrl, key)
        return true
    }

    private suspend fun register(serverId: String, baseUrl: String, key: String) {
        ensureLoaded()
        AbsServer.normalizeBaseUrl(baseUrl)?.let { registry.register(AbsServer(serverId, it, key)) }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val loadedServers = bookDao.servers().mapNotNull { entity ->
                val key = secretStore.get(tokenKey(entity.serverId))
                val baseUrl = AbsServer.normalizeBaseUrl(entity.baseUrl)
                if (key == null || baseUrl == null) {
                    Log.w(TAG, "Server ${entity.name} is incompletely set up and is skipped")
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
        const val FRESH_FOR_SECONDS = 30 * 60L

        fun tokenKey(serverId: String) = AbsSecrets.token(serverId)
    }
}
