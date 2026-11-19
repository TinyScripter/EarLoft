package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsTokenRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Names of an Audiobookshelf server's secrets in the [SecretStore]. */
internal object AbsSecrets {
    /** API key or access token. */
    fun token(serverId: String) = "abs:$serverId:token"

    /** Only for login via OpenID with server 2.26 or later; never leaves the phone (not even to the watch). */
    fun refresh(serverId: String) = "abs:$serverId:refresh"

    /** Random per OpenID login: basis of the stamp for the watch, which doesn't change with every access token. */
    fun login(serverId: String) = "abs:$serverId:login"
}

/**
 * Renews access tokens via refresh token (concept 12.11). At most one renewal runs per server, because the refresh token
 * rotates: two simultaneous renewals with the same one would cost the login after the grace period.
 */
@Singleton
class AbsTokenRenewal @Inject constructor(
    private val secretStore: SecretStore,
    private val registry: AbsServerRegistry,
    private val authApi: AbsAuthApi,
) : AbsTokenRefresher {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Superseded access tokens per server: requests still running with one of them retry with the current one. */
    private val retired = ConcurrentHashMap<String, List<String>>()

    private val _expired = MutableStateFlow(0)

    /** Increments when a login has expired and its tokens have been discarded. */
    val expired: StateFlow<Int> = _expired.asStateFlow()

    override fun renewBlocking(serverId: String, rejected: String): String? = runBlocking { renew(serverId, rejected) }

    suspend fun renew(serverId: String, rejected: String): String? = lock(serverId).withLock {
        val current = registry[serverId] ?: return null
        when (rejected) {
            current.token -> refreshLocked(current)
            in retired[serverId].orEmpty() -> current.token
            else -> null
        }
    }

    /**
     * The current token, for an access token still valid for at least [minValiditySeconds]; renewed beforehand if necessary. For
     * recipients that can't renew themselves (cast, watch). `null` if the server isn't configured.
     */
    suspend fun fresh(serverId: String, minValiditySeconds: Long): String? = lock(serverId).withLock {
        val current = registry[serverId] ?: return null
        val expiresAt = AbsAuthApi.expiresAt(current.token) ?: return current.token
        if (expiresAt - System.currentTimeMillis() / 1000 >= minValiditySeconds) return current.token
        refreshLocked(current) ?: registry[serverId]?.token
    }

    /** After a new login or removal: old tokens no longer count as this server's. */
    fun forget(serverId: String) {
        retired.remove(serverId)
    }

    /**
     * Not cancellable (review D32): once the server has rotated the refresh token, the old one only remains valid briefly. If the
     * caller cancelled in the meantime and the new tokens stayed unstored, that would later cost the login.
     */
    private suspend fun refreshLocked(server: AbsServer): String? = withContext(NonCancellable) { refreshNow(server) }

    private suspend fun refreshNow(server: AbsServer): String? {
        val refreshToken = secretStore.get(AbsSecrets.refresh(server.serverId)) ?: return null
        val tokens = try {
            withContext(Dispatchers.IO) { authApi.refreshBlocking(server.baseUrl, refreshToken) }
        } catch (e: AbsException) {
            if (e.code == 401) {
                Log.w(TAG, "Login at ${server.baseUrl} expired", e)
                expire(server.serverId)
            } else {
                Log.w(TAG, "Renewal at ${server.baseUrl} failed", e)
            }
            return null
        } catch (e: IOException) {
            Log.w(TAG, "Renewal at ${server.baseUrl} not possible", e)
            return null
        }
        secretStore.put(AbsSecrets.token(server.serverId), tokens.accessToken)
        tokens.refreshToken?.let { secretStore.put(AbsSecrets.refresh(server.serverId), it) }
        retired[server.serverId] = (listOf(server.token) + retired[server.serverId].orEmpty()).take(RETIRED_KEPT)
        registry.register(server.copy(token = tokens.accessToken))
        Log.d(TAG, "Access token for ${server.baseUrl} renewed")
        return tokens.accessToken
    }

    /** Like a missing API key: the server stays configured, the list asks for a new login. */
    private suspend fun expire(serverId: String) {
        secretStore.remove(AbsSecrets.token(serverId))
        secretStore.remove(AbsSecrets.refresh(serverId))
        secretStore.remove(AbsSecrets.login(serverId))
        registry.unregister(serverId)
        retired.remove(serverId)
        _expired.value++
    }

    private fun lock(serverId: String): Mutex = locks.computeIfAbsent(serverId) { Mutex() }

    private companion object {
        const val TAG = "AbsTokenRenewal"
        const val RETIRED_KEPT = 3
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface AbsTokenRenewalModule {
    @Binds
    fun bindRefresher(renewal: AbsTokenRenewal): AbsTokenRefresher
}
