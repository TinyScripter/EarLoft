package de.kleinabersmart.earloft.core.data.spoken

import android.util.Log
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.wear.CredentialsStamps
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServerRegistry
import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import de.kleinabersmart.earloft.core.model.SpokenServer
import kotlinx.coroutines.flow.Flow
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
    data object NoBookLibrary : AddServerResult
}

/** Ein Server zum Bearbeiten; der API-Key selbst verlässt den [SecretStore] nicht. */
data class EditableAbsServer(val name: String, val baseUrl: String, val hasKey: Boolean)

/**
 * Ein Server, wie das Handy ihn der Uhr übergibt (ohne Schlüssel, Wear-Konzept 16.2). [credentialsStamp] ändert sich mit dem
 * Schlüssel ([CredentialsStamps]); `null`, wenn das Handy keinen hat.
 */
data class MirroredAbsServer(val serverId: String, val name: String, val baseUrl: String, val credentialsStamp: String? = null)

/** Eingerichtete Audiobookshelf-Server (Konzept 12.1); der API-Key liegt im [SecretStore]. */
@Singleton
class AudiobookshelfServers @Inject constructor(
    private val bookDao: BookDao,
    private val secretStore: SecretStore,
    private val api: AbsApi,
    private val registry: AbsServerRegistry,
    private val stamps: CredentialsStamps,
) {
    val servers: Flow<List<SpokenServer>> = bookDao.observeServers().map { list ->
        list.map { SpokenServer(it.serverId, it.name, it.baseUrl, it.lastFullSyncAt, it.enabled) }
    }

    /**
     * Handy: die eingeschalteten Server für die Uhr. Ausgeschaltete fehlen, ihre Bücher sind am Handy ausgeblendet und fallen von
     * der Uhr. Ein neuer Schlüssel schreibt auch den Server neu ([connect]); damit kommt ein neuer Stempel.
     */
    val mirrored: Flow<List<MirroredAbsServer>> = bookDao.observeServers().map { list ->
        list.filter { it.enabled }.map {
            MirroredAbsServer(it.serverId, it.name, it.baseUrl, stamps.absStamp(it.serverId, secretStore.get(tokenKey(it.serverId))))
        }
    }

    /**
     * Server, deren API-Key fehlt (z. B. nach einer Wiederherstellung auf einem anderen Gerät); der Nutzer muss ihn beim
     * Bearbeiten neu eingeben. Neu berechnet, wenn sich die Server ändern – das Speichern eines Keys ändert sie mit.
     */
    val serversWithoutKey: Flow<Set<String>> = bookDao.observeServers().map { list ->
        list.filter { secretStore.get(tokenKey(it.serverId)) == null }.mapTo(HashSet()) { it.serverId }
    }

    private val mutex = Mutex()

    @Volatile
    private var loaded = false

    /** Alle eingeschalteten Server, deren Key sich lesen lässt. */
    suspend fun all(): List<AbsServer> {
        ensureLoaded()
        return bookDao.servers().filter { it.enabled }.mapNotNull { registry[it.serverId] }
    }

    suspend fun server(serverId: String): AbsServer? {
        ensureLoaded()
        return registry[serverId]
    }

    /** Wie [all], aber auch ausgeschaltete Server, wie bei [server]. */
    suspend fun allIncludingDisabled(): List<AbsServer> {
        ensureLoaded()
        return bookDao.servers().mapNotNull { registry[it.serverId] }
    }

    suspend fun add(name: String, baseUrlInput: String, apiKey: String): AddServerResult =
        connect(null, name, baseUrlInput, apiKey)

    /**
     * Ändert Name, Adresse oder API-Key eines Servers, nur wenn die Verbindung mit den neuen Angaben klappt.
     * Die `serverId` bleibt, damit Bücher, Fortschritt und Downloads erhalten bleiben. [apiKey] leer = der
     * gespeicherte Key bleibt. Zeigt die Adresse auf eine andere Bibliothek, räumt der nächste Vollabgleich auf.
     */
    suspend fun update(serverId: String, name: String, baseUrlInput: String, apiKey: String): AddServerResult =
        connect(serverId, name, baseUrlInput, apiKey)

    /** Schaltet den Server ein oder aus; ausgeschaltet bleiben Bücher und Downloads erhalten, sind aber ausgeblendet. */
    suspend fun setEnabled(serverId: String, enabled: Boolean) {
        bookDao.setServerEnabled(serverId, enabled)
    }

    /** Eingeschaltete Server ohne gespeicherten API-Key (nach dem Wiederherstellen auf einem neuen Gerät). */
    suspend fun withoutKey(): List<String> {
        val missing = serversWithoutKey.first()
        return bookDao.servers().filter { it.enabled && it.serverId in missing }.map { it.serverId }
    }

    suspend fun editable(serverId: String): EditableAbsServer? {
        val entity = bookDao.server(serverId) ?: return null
        return EditableAbsServer(entity.name, entity.baseUrl, hasKey = secretStore.get(tokenKey(serverId)) != null)
    }

    private suspend fun connect(existingId: String?, name: String, baseUrlInput: String, apiKey: String): AddServerResult {
        val baseUrl = AbsServer.normalizeBaseUrl(baseUrlInput) ?: return AddServerResult.InvalidUrl
        val existing = existingId?.let { bookDao.server(it) }
        if (existingId != null && existing == null) return AddServerResult.InvalidUrl
        val key = apiKey.trim().ifEmpty { existingId?.let { secretStore.get(tokenKey(it)) }.orEmpty() }
        if (key.isEmpty()) return AddServerResult.AuthFailed
        val candidate = AbsServer(existingId ?: UUID.randomUUID().toString(), baseUrl, key)
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
        // Sync-Generation, Zeitpunkt und Schalter bleiben, sonst hielte der Mark-and-Sweep alles für neu.
        bookDao.upsertServer(
            (existing ?: SpokenServerEntity(candidate.serverId, name, baseUrl.toString())).copy(
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

    // --- Uhr (Wear-Konzept 16.2): Server ohne Schlüssel gespiegelt, der Schlüssel einzeln auf Anfrage ---

    /** Handy: der Schlüssel für die anfragende Uhr; `null`, wenn [serverId] kein Audiobookshelf-Server ist. */
    suspend fun apiKey(serverId: String): String? =
        if (bookDao.server(serverId) != null) secretStore.get(tokenKey(serverId)) else null

    /**
     * Uhr: übernimmt die Server des Handys. Fehlt einer dort, verschwindet er samt seinen Büchern und dem Schlüssel; die
     * übrigen behalten ihre Bücher (gleiche `serverId`).
     * @return die Server, deren Schlüssel verworfen ist, weil er sich am Handy geändert hat oder zum alten Host gehört: Die
     * Uhr fragt ihn gleich neu an.
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
                    // Neue Adresse auf demselben Host: mit dem gespeicherten Schlüssel neu eintragen.
                    secretStore.get(tokenKey(server.serverId))?.let { key -> register(server.serverId, server.baseUrl, key) }
                } else {
                    // Anderer Host: Der Schlüssel bleibt beim alten (die Uhr fragt ihn beim Handy neu an).
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

    /** Uhr: @return `false`, wenn der Server (noch) nicht gespiegelt ist. */
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
