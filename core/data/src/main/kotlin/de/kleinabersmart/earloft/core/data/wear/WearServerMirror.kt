package de.kleinabersmart.earloft.core.data.wear

import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.data.security.SecretStore
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.source.JellyfinSecret
import de.kleinabersmart.earloft.core.data.sync.LibraryChangeListener
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.network.acceptsCredentialsOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The watch's server access (Wear concept 6). The phone provides the servers without password ([servers]) and
 * the password separately on request ([password]); the watch mirrors both ([apply], [storePassword]).
 * The `sourceId` is the same on both devices. For Jellyfin the "password" is the phone's [JellyfinSecret]:
 * the watch shares its token (concept 14); only the phone is signed in.
 */
@Singleton
class WearServerMirror @Inject constructor(
    private val database: EarLoftDatabase,
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val secretStore: SecretStore,
    private val registry: DataSourceRegistry,
    private val stamps: CredentialsStamps,
    private val changeListeners: Set<@JvmSuppressWildcards LibraryChangeListener>,
) {
    // --- Phone ---

    /** New credentials also rewrite the server (`ServerRepository`); that brings a new [MirroredServer.credentialsStamp]. */
    fun servers(): Flow<List<MirroredServer>> =
        sourceDao.observeServers().map { sources -> sources.mapNotNull { it.toMirrored(stamps.stamp(it.sourceId)) } }

    suspend fun password(sourceId: String): String? =
        if (sourceDao.get(sourceId)?.type?.isServer == true) secretStore.get(DataSourceRegistry.passwordKey(sourceId)) else null

    /** Jellyfin and Emby: the device the token from [password] belongs to; `null` for sign-ins from before. */
    suspend fun deviceId(sourceId: String): String? =
        if (sourceDao.get(sourceId)?.type?.isServer == true) secretStore.get(DataSourceRegistry.deviceIdKey(sourceId)) else null

    // --- Watch ---

    /**
     * Adopts the phone's servers. Servers missing there disappear along with their songs, downloads and
     * passwords; sync state and songs of the others stay (same `sourceId`, e.g. after editing).
     * @return the servers whose credentials were discarded because they changed on the phone or the access (host,
     * user) is a different one: the watch requests them again right away.
     */
    suspend fun apply(servers: List<MirroredServer>): Set<String> {
        val renewed = stamps.renewChanged(servers)
        if (renewed.isNotEmpty()) registry.reload()
        return renewed + applyServers(servers)
    }

    /** @return the servers with different access. */
    private suspend fun applyServers(servers: List<MirroredServer>): List<String> {
        val existing = sourceDao.getServers().associateBy { it.sourceId }
        val updated = servers.map { server ->
            (existing[server.sourceId] ?: SourceEntity(server.sourceId, server.type, server.name)).copy(
                type = server.type,
                name = server.name,
                baseUrl = server.baseUrl,
                username = server.username,
                priority = server.priority,
                // As on the phone, a JSON list of the extensions; the watch needs no more than `formPost`.
                serverCapabilities = if (server.formPost) FORM_POST_CAPABILITIES else null,
            )
        }
        val removed = existing.keys - servers.mapTo(HashSet()) { it.sourceId }
        if (updated.all { it == existing[it.sourceId] } && removed.isEmpty()) return emptyList()
        // Different host or user: the secret belongs to the old access and would otherwise go to the new host
        // (credentials stay on their host); the next round requests it again from the phone.
        val moved = updated.filter { new -> existing[new.sourceId]?.let { old -> !sameAccess(old, new) } == true }.map { it.sourceId }

        database.withTransaction {
            updated.forEach { sourceDao.upsert(it) }
            removed.forEach { sourceDao.delete(it) }
            if (removed.isNotEmpty()) libraryDao.cleanupOrphans()
        }
        (removed + moved).forEach {
            secretStore.remove(DataSourceRegistry.passwordKey(it))
            secretStore.remove(DataSourceRegistry.deviceIdKey(it))
        }
        removed.forEach { stamps.forget(it) }
        registry.reload()
        if (removed.isNotEmpty()) changeListeners.forEach { it.onSongSourcesRemoved() }
        return moved
    }

    private fun sameAccess(old: SourceEntity, new: SourceEntity): Boolean {
        if (old.username != new.username) return false
        val oldUrl = old.baseUrl?.toHttpUrlOrNull() ?: return false
        return new.baseUrl?.toHttpUrlOrNull()?.acceptsCredentialsOf(oldUrl) == true
    }

    /** Servers for which the watch doesn't have a (readable) password yet. */
    suspend fun serversWithoutPassword(): List<String> =
        sourceDao.getServers()
            .map { it.sourceId }
            .filter { secretStore.get(DataSourceRegistry.passwordKey(it)) == null }

    /** [deviceId] see [deviceId]. @return `false` if the server is not mirrored (yet). */
    suspend fun storePassword(sourceId: String, password: String, deviceId: String? = null): Boolean {
        if (sourceDao.get(sourceId)?.type?.isServer != true) return false
        secretStore.put(DataSourceRegistry.passwordKey(sourceId), password)
        if (deviceId != null) {
            secretStore.put(DataSourceRegistry.deviceIdKey(sourceId), deviceId)
        } else {
            secretStore.remove(DataSourceRegistry.deviceIdKey(sourceId))
        }
        registry.reload()
        return true
    }

    private fun SourceEntity.toMirrored(credentialsStamp: String?): MirroredServer? = MirroredServer(
        sourceId = sourceId,
        type = type,
        name = name,
        baseUrl = baseUrl ?: return null,
        username = username ?: return null,
        priority = priority,
        formPost = serverCapabilities?.contains(FORM_POST) == true,
        credentialsStamp = credentialsStamp,
    )

    private companion object {
        const val FORM_POST = "\"formPost\""
        const val FORM_POST_CAPABILITIES = "[$FORM_POST]"
    }
}
