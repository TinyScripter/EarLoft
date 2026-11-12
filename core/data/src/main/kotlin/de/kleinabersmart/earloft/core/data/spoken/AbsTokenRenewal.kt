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

/** Namen der Geheimnisse eines Audiobookshelf-Servers im [SecretStore]. */
internal object AbsSecrets {
    /** API-Key oder Access-Token. */
    fun token(serverId: String) = "abs:$serverId:token"

    /** Nur bei Anmeldung per OpenID mit Server ab 2.26; verlässt das Handy nie (auch nicht zur Uhr). */
    fun refresh(serverId: String) = "abs:$serverId:refresh"

    /** Zufällig je Anmeldung per OpenID: Grundlage des Stempels für die Uhr, der sich nicht mit jedem Access-Token ändert. */
    fun login(serverId: String) = "abs:$serverId:login"
}

/**
 * Erneuert Access-Tokens per Refresh-Token (Konzept 12.11). Je Server läuft höchstens eine Erneuerung, denn der Refresh-Token
 * rotiert: Zwei gleichzeitige Erneuerungen mit demselben würden nach der Gnadenfrist die Anmeldung kosten.
 */
@Singleton
class AbsTokenRenewal @Inject constructor(
    private val secretStore: SecretStore,
    private val registry: AbsServerRegistry,
    private val authApi: AbsAuthApi,
) : AbsTokenRefresher {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Abgelöste Access-Tokens je Server: Anfragen, die noch mit einem davon liefen, wiederholen mit dem aktuellen. */
    private val retired = ConcurrentHashMap<String, List<String>>()

    private val _expired = MutableStateFlow(0)

    /** Zählt hoch, wenn eine Anmeldung abgelaufen ist und ihre Tokens verworfen sind. */
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
     * Der aktuelle Token, beim Access-Token noch mindestens [minValiditySeconds] gültig; wenn nötig, vorher erneuert. Für
     * Empfänger, die selbst nicht erneuern können (Cast, Uhr). `null`, wenn der Server nicht eingerichtet ist.
     */
    suspend fun fresh(serverId: String, minValiditySeconds: Long): String? = lock(serverId).withLock {
        val current = registry[serverId] ?: return null
        val expiresAt = AbsAuthApi.expiresAt(current.token) ?: return current.token
        if (expiresAt - System.currentTimeMillis() / 1000 >= minValiditySeconds) return current.token
        refreshLocked(current) ?: registry[serverId]?.token
    }

    /** Nach neuer Anmeldung oder Entfernen: alte Tokens gelten nicht mehr als die dieses Servers. */
    fun forget(serverId: String) {
        retired.remove(serverId)
    }

    private suspend fun refreshLocked(server: AbsServer): String? {
        val refreshToken = secretStore.get(AbsSecrets.refresh(server.serverId)) ?: return null
        val tokens = try {
            withContext(Dispatchers.IO) { authApi.refreshBlocking(server.baseUrl, refreshToken) }
        } catch (e: AbsException) {
            if (e.code == 401) {
                Log.w(TAG, "Anmeldung bei ${server.baseUrl} abgelaufen", e)
                expire(server.serverId)
            } else {
                Log.w(TAG, "Erneuern bei ${server.baseUrl} fehlgeschlagen", e)
            }
            return null
        } catch (e: IOException) {
            Log.w(TAG, "Erneuern bei ${server.baseUrl} nicht möglich", e)
            return null
        }
        secretStore.put(AbsSecrets.token(server.serverId), tokens.accessToken)
        tokens.refreshToken?.let { secretStore.put(AbsSecrets.refresh(server.serverId), it) }
        retired[server.serverId] = (listOf(server.token) + retired[server.serverId].orEmpty()).take(RETIRED_KEPT)
        registry.register(server.copy(token = tokens.accessToken))
        Log.d(TAG, "Access-Token für ${server.baseUrl} erneuert")
        return tokens.accessToken
    }

    /** Wie ein fehlender API-Key: Der Server bleibt eingerichtet, die Liste bittet um neue Anmeldung. */
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
