package de.kleinabersmart.tinyamp.core.data.repository

import androidx.room.withTransaction
import de.kleinabersmart.tinyamp.core.data.security.SecretStore
import de.kleinabersmart.tinyamp.core.data.source.DataSourceRegistry
import de.kleinabersmart.tinyamp.core.data.sync.LibraryChangeListener
import de.kleinabersmart.tinyamp.core.data.sync.LibraryRefresher
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import de.kleinabersmart.tinyamp.core.database.dao.PlaylistDao
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.model.ServerInfo
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.network.subsonic.HttpStatusException
import de.kleinabersmart.tinyamp.core.network.subsonic.NotSubsonicException
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicApi
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicException
import de.kleinabersmart.tinyamp.core.network.subsonic.SubsonicServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    data object NotSubsonic : ConnectionResult
    data class Unreachable(val reason: String?) : ConnectionResult
    data class ServerError(val code: Int, val message: String?) : ConnectionResult
}

/** Ein Server zum Bearbeiten; das Passwort selbst verlässt das Repository nicht. */
data class EditableServer(val info: ServerInfo, val hasPassword: Boolean)

/** Einrichten, Prüfen, Bearbeiten und Entfernen von Navidrome-/Subsonic-Servern. */
@Singleton
class ServerRepository @Inject constructor(
    private val database: TinyAmpDatabase,
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val playlistDao: PlaylistDao,
    private val secretStore: SecretStore,
    private val registry: DataSourceRegistry,
    private val api: SubsonicApi,
    private val refresher: LibraryRefresher,
    private val changeListeners: Set<@JvmSuppressWildcards LibraryChangeListener>,
) {
    fun servers(): Flow<List<ServerInfo>> =
        sourceDao.observeByType(SourceType.SUBSONIC).map { sources -> sources.map { it.toServerInfo() } }

    /** @return `null`, wenn es den Server nicht (mehr) gibt. */
    suspend fun editableServer(sourceId: String): EditableServer? {
        val entity = sourceDao.get(sourceId)?.takeIf { it.type == SourceType.SUBSONIC } ?: return null
        return EditableServer(entity.toServerInfo(), hasPassword = storedPassword(sourceId) != null)
    }

    /**
     * Testet die Verbindung eines vorhandenen Servers mit geänderten Angaben. [newPassword] `null`
     * = das gespeicherte Passwort verwenden.
     */
    suspend fun testConnection(sourceId: String, url: String, username: String, newPassword: String?): ConnectionResult {
        val password = newPassword ?: storedPassword(sourceId) ?: return ConnectionResult.WrongCredentials
        return testConnection(url, username, password)
    }

    suspend fun testConnection(url: String, username: String, password: String): ConnectionResult {
        val baseUrl = SubsonicServer.normalizeBaseUrl(url) ?: return ConnectionResult.InvalidUrl
        val server = SubsonicServer(TEST_SOURCE_ID, baseUrl, SubsonicCredentials.Password(username, password))
        return try {
            val pong = api.ping(server)
            ConnectionResult.Success(
                serverName = pong.type ?: baseUrl.host,
                serverVersion = pong.serverVersion ?: pong.version,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubsonicException) {
            if (e.isAuthError) ConnectionResult.WrongCredentials else ConnectionResult.ServerError(e.code, e.serverMessage)
        } catch (_: NotSubsonicException) {
            ConnectionResult.NotSubsonic
        } catch (e: HttpStatusException) {
            if (e.status == 404) ConnectionResult.NotSubsonic else ConnectionResult.Unreachable(e.message)
        } catch (e: IOException) {
            ConnectionResult.Unreachable(e.message)
        }
    }

    /**
     * Prüft die Verbindung und legt den Server nur bei Erfolg an. Danach startet sofort der erste
     * Abgleich. @return das Testergebnis.
     */
    suspend fun addServer(name: String, url: String, username: String, password: String): ConnectionResult {
        val result = testConnection(url, username, password)
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
        val password = newPassword ?: storedPassword(sourceId) ?: return ConnectionResult.WrongCredentials
        val result = testConnection(url, username, password)
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
    )

    private companion object {
        const val TEST_SOURCE_ID = "connection-test"
    }
}
