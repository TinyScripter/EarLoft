package de.kleinabersmart.tinyamp.core.data.repository

import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.tinyamp.core.data.security.SecretStore
import de.kleinabersmart.tinyamp.core.data.source.DataSourceRegistry
import de.kleinabersmart.tinyamp.core.data.source.JellyfinSecret
import de.kleinabersmart.tinyamp.core.data.sync.LibraryChangeListener
import de.kleinabersmart.tinyamp.core.data.sync.LibraryRefresher
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import de.kleinabersmart.tinyamp.core.database.dao.PlaylistDao
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.model.ServerInfo
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.network.HttpStatusException
import de.kleinabersmart.tinyamp.core.network.ServerApiException
import de.kleinabersmart.tinyamp.core.network.UnexpectedResponseException
import de.kleinabersmart.tinyamp.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.tinyamp.core.network.jellyfin.JellyfinServer
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicApi
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Ergebnis eines Verbindungstests. */
sealed interface ConnectionResult {
    /** [sourceId]: nur nach [ServerRepository.addServer] gesetzt – die ID des neuen Servers. */
    data class Success(val serverName: String, val serverVersion: String?, val sourceId: String? = null) : ConnectionResult
    data object InvalidUrl : ConnectionResult
    data object WrongCredentials : ConnectionResult

    /** Unter der Adresse antwortet kein Server der gewählten Art. */
    data object UnexpectedServer : ConnectionResult
    data class Unreachable(val reason: String?) : ConnectionResult
    data class ServerError(val code: Int, val message: String?) : ConnectionResult
}

/** Ein Server zum Bearbeiten; Passwort bzw. Token verlassen das Repository nicht. */
data class EditableServer(val info: ServerInfo, val hasPassword: Boolean)

/** Einrichten, Prüfen, Bearbeiten und Entfernen von Musikservern (Navidrome/Subsonic, Jellyfin). */
@Singleton
class ServerRepository @Inject constructor(
    private val database: TinyAmpDatabase,
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val playlistDao: PlaylistDao,
    private val secretStore: SecretStore,
    private val registry: DataSourceRegistry,
    private val api: SubsonicApi,
    private val jellyfinApi: JellyfinApi,
    private val refresher: LibraryRefresher,
    private val changeListeners: Set<@JvmSuppressWildcards LibraryChangeListener>,
) {
    fun servers(): Flow<List<ServerInfo>> =
        sourceDao.observeServers().map { sources -> sources.map { it.toServerInfo() } }

    /** @return `null`, wenn es den Server nicht (mehr) gibt. */
    suspend fun editableServer(sourceId: String): EditableServer? {
        val entity = sourceDao.get(sourceId)?.takeIf { it.type.isServer } ?: return null
        val hasSecret = when (entity.type) {
            SourceType.JELLYFIN -> storedJellyfinSecret(sourceId) != null
            else -> storedPassword(sourceId) != null
        }
        return EditableServer(entity.toServerInfo(), hasPassword = hasSecret)
    }

    /**
     * Prüft die Verbindung und legt den Server nur bei Erfolg an. Danach startet sofort der erste
     * Abgleich. @return das Testergebnis.
     */
    suspend fun addServer(type: SourceType, name: String, url: String, username: String, password: String): ConnectionResult =
        when (type) {
            SourceType.JELLYFIN -> addJellyfinServer(name, url, username, password)
            else -> addSubsonicServer(name, url, username, password)
        }

    /**
     * Ändert Name, Adresse, Benutzer oder Passwort eines Servers, nur wenn die Verbindung mit den
     * neuen Angaben klappt. Die `sourceId` bleibt, damit Songs, Downloads, Favoriten und Playlists
     * erhalten bleiben (und die Uhr nichts neu laden muss). [newPassword] `null` = unverändert.
     *
     * Danach folgt ein Vollabgleich: Zeigt die Adresse jetzt auf eine andere Bibliothek, räumt der
     * Mark-and-Sweep die alten Songs ab.
     */
    suspend fun updateServer(
        sourceId: String,
        name: String,
        url: String,
        username: String,
        newPassword: String?,
    ): ConnectionResult {
        val existing = sourceDao.get(sourceId) ?: return ConnectionResult.InvalidUrl
        return when (existing.type) {
            SourceType.JELLYFIN -> updateJellyfinServer(existing, name, url, username, newPassword)
            else -> updateSubsonicServer(existing, name, url, username, newPassword)
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

        secretStore.put(DataSourceRegistry.passwordKey(sourceId), password)
        sourceDao.upsert(
            SourceEntity(
                sourceId = sourceId,
                type = SourceType.SUBSONIC,
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                username = username,
                serverCapabilities = capabilities(server),
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
        val password = newPassword ?: storedPassword(sourceId) ?: return ConnectionResult.WrongCredentials
        val result = testSubsonic(url, username, password)
        if (result !is ConnectionResult.Success) return result

        val baseUrl = SubsonicServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val server = SubsonicServer(sourceId, baseUrl, SubsonicCredentials.Password(username, password))
        if (newPassword != null) secretStore.put(DataSourceRegistry.passwordKey(sourceId), newPassword)
        // Sync-Generation und Zeitpunkt bleiben, sonst hielte der Mark-and-Sweep alles für neu.
        sourceDao.upsert(
            existing.copy(
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                username = username,
                serverCapabilities = capabilities(server),
            ),
        )
        registry.reload()
        refresher.refreshSource(sourceId)
        return result
    }

    // --- Jellyfin (Konzept 14): gespeichert werden nur Benutzer-ID und Token, nie das Passwort ---

    /** Meldet das Gerät der Quelle [sourceId] an; ein früherer Token dieses Geräts wird damit ungültig. */
    private suspend fun loginJellyfin(
        sourceId: String,
        baseUrl: HttpUrl,
        username: String,
        password: String,
    ): Pair<ConnectionResult, JellyfinSecret?> =
        try {
            val info = jellyfinApi.publicInfo(baseUrl)
            val auth = jellyfinApi.authenticate(baseUrl, JellyfinServer.deviceIdFor(sourceId), username, password)
            ConnectionResult.Success(info.productName ?: JELLYFIN, info.version) to JellyfinSecret(auth.user.id, auth.accessToken)
        } catch (e: IOException) {
            connectionFailure(e) to null
        }

    private suspend fun addJellyfinServer(name: String, url: String, username: String, password: String): ConnectionResult {
        val baseUrl = JellyfinServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val sourceId = UUID.randomUUID().toString()
        val (result, secret) = loginJellyfin(sourceId, baseUrl, username, password)
        if (result !is ConnectionResult.Success || secret == null) return result

        secretStore.put(DataSourceRegistry.passwordKey(sourceId), secret.encode())
        sourceDao.upsert(
            SourceEntity(
                sourceId = sourceId,
                type = SourceType.JELLYFIN,
                name = name.trim().ifEmpty { baseUrl.host },
                baseUrl = baseUrl.toString(),
                username = username,
            ),
        )
        registry.reload()
        refresher.refreshSource(sourceId)
        return result.copy(sourceId = sourceId)
    }

    /** Ohne neues Passwort bleibt der Token, solange er unter der (neuen) Adresse zum Konto [username] gehört. */
    private suspend fun updateJellyfinServer(
        existing: SourceEntity,
        name: String,
        url: String,
        username: String,
        newPassword: String?,
    ): ConnectionResult {
        val sourceId = existing.sourceId
        val baseUrl = JellyfinServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val result = if (newPassword != null) {
            val (result, secret) = loginJellyfin(sourceId, baseUrl, username, newPassword)
            if (result !is ConnectionResult.Success || secret == null) return result
            secretStore.put(DataSourceRegistry.passwordKey(sourceId), secret.encode())
            result
        } else {
            val stored = storedJellyfinSecret(sourceId) ?: return ConnectionResult.WrongCredentials
            try {
                val info = jellyfinApi.publicInfo(baseUrl)
                val me = jellyfinApi.me(JellyfinServer(sourceId, baseUrl, stored.userId, stored.token))
                if (!me.name.equals(username.trim(), ignoreCase = true)) return ConnectionResult.WrongCredentials
                ConnectionResult.Success(info.productName ?: JELLYFIN, info.version)
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

    /** Gibt den Token frei; offline oder ohne Antwort bleibt er eben auf dem Server stehen. */
    private suspend fun logoutJellyfin(entity: SourceEntity) {
        val baseUrl = entity.baseUrl?.toHttpUrlOrNull() ?: return
        val secret = storedJellyfinSecret(entity.sourceId) ?: return
        try {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) {
                jellyfinApi.logout(JellyfinServer(entity.sourceId, baseUrl, secret.userId, secret.token))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Abmelden bei ${entity.name} fehlgeschlagen: ${e.message}")
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
     * Schaltet den Server ein oder aus. Ausgeschaltet bleibt alles erhalten (Songs, Downloads, Favoriten,
     * Playlists); nur Anzeige, Wiedergabe-Quellen und Abgleich ruhen. Einschalten gleicht sofort ab.
     */
    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        sourceDao.setEnabled(sourceId, enabled)
        registry.reload()
        if (enabled) refresher.refreshSource(sourceId)
    }

    /** Entfernt den Server samt aller Songs, die nur er geliefert hat. */
    suspend fun removeServer(sourceId: String) {
        sourceDao.get(sourceId)?.takeIf { it.type == SourceType.JELLYFIN }?.let { logoutJellyfin(it) }
        database.withTransaction {
            sourceDao.delete(sourceId)
            // Die Playlists des Servers liegen weiter dort; lokal verschwinden sie mit ihm.
            playlistDao.deleteBoundTo(sourceId)
            libraryDao.cleanupOrphans()
        }
        secretStore.remove(DataSourceRegistry.passwordKey(sourceId))
        registry.reload()
        changeListeners.forEach { it.onSongSourcesRemoved() }
    }

    fun syncNow(sourceId: String) = refresher.refreshSource(sourceId)

    private suspend fun storedPassword(sourceId: String): String? =
        secretStore.get(DataSourceRegistry.passwordKey(sourceId))

    private suspend fun storedJellyfinSecret(sourceId: String): JellyfinSecret? =
        storedPassword(sourceId)?.let(JellyfinSecret::decode)

    /** OpenSubsonic-Extensions als JSON-Liste, siehe [DataSourceRegistry]. */
    private suspend fun capabilities(server: SubsonicServer): String =
        api.openSubsonicExtensions(server).joinToString(",", prefix = "[", postfix = "]") { "\"${it.name}\"" }

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
        const val JELLYFIN = "Jellyfin"
        const val LOGOUT_TIMEOUT_MS = 5_000L
    }
}
