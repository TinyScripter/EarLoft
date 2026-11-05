package de.kleinabersmart.tinyamp.core.data.wear

import androidx.room.withTransaction
import de.kleinabersmart.tinyamp.core.data.security.SecretStore
import de.kleinabersmart.tinyamp.core.data.source.DataSourceRegistry
import de.kleinabersmart.tinyamp.core.data.sync.LibraryChangeListener
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-Zugang der Uhr (Wear-Konzept 6). Das Handy liefert die Server ohne Passwort ([servers]) und
 * das Passwort einzeln auf Anfrage ([password]); die Uhr spiegelt beides ([apply], [storePassword]).
 * Die `sourceId` ist auf beiden Geräten dieselbe.
 */
@Singleton
class WearServerMirror @Inject constructor(
    private val database: TinyAmpDatabase,
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val secretStore: SecretStore,
    private val registry: DataSourceRegistry,
    private val changeListeners: Set<@JvmSuppressWildcards LibraryChangeListener>,
) {
    // --- Handy ---

    fun servers(): Flow<List<MirroredServer>> =
        sourceDao.observeByType(SourceType.SUBSONIC).map { sources -> sources.mapNotNull { it.toMirrored() } }

    suspend fun password(sourceId: String): String? =
        if (sourceDao.get(sourceId)?.type == SourceType.SUBSONIC) secretStore.get(DataSourceRegistry.passwordKey(sourceId)) else null

    // --- Uhr ---

    /**
     * Übernimmt die Server des Handys. Server, die dort fehlen, verschwinden mit ihren Songs, Downloads und
     * Passwörtern; Sync-Zustand und Songs der übrigen bleiben (gleiche `sourceId`, z. B. nach dem Bearbeiten).
     */
    suspend fun apply(servers: List<MirroredServer>) {
        val existing = sourceDao.getByType(SourceType.SUBSONIC).associateBy { it.sourceId }
        val updated = servers.map { server ->
            (existing[server.sourceId] ?: SourceEntity(server.sourceId, SourceType.SUBSONIC, server.name)).copy(
                name = server.name,
                baseUrl = server.baseUrl,
                username = server.username,
                priority = server.priority,
                // Wie am Handy eine JSON-Liste der Extensions; mehr als `formPost` braucht die Uhr nicht.
                serverCapabilities = if (server.formPost) FORM_POST_CAPABILITIES else null,
            )
        }
        val removed = existing.keys - servers.mapTo(HashSet()) { it.sourceId }
        if (updated.all { it == existing[it.sourceId] } && removed.isEmpty()) return

        database.withTransaction {
            updated.forEach { sourceDao.upsert(it) }
            removed.forEach { sourceDao.delete(it) }
            if (removed.isNotEmpty()) libraryDao.cleanupOrphans()
        }
        removed.forEach { secretStore.remove(DataSourceRegistry.passwordKey(it)) }
        registry.reload()
        if (removed.isNotEmpty()) changeListeners.forEach { it.onSongSourcesRemoved() }
    }

    /** Server, für die die Uhr noch kein (lesbares) Passwort hat. */
    suspend fun serversWithoutPassword(): List<String> =
        sourceDao.getByType(SourceType.SUBSONIC)
            .map { it.sourceId }
            .filter { secretStore.get(DataSourceRegistry.passwordKey(it)) == null }

    /** @return `false`, wenn der Server (noch) nicht gespiegelt ist. */
    suspend fun storePassword(sourceId: String, password: String): Boolean {
        if (sourceDao.get(sourceId)?.type != SourceType.SUBSONIC) return false
        secretStore.put(DataSourceRegistry.passwordKey(sourceId), password)
        registry.reload()
        return true
    }

    private fun SourceEntity.toMirrored(): MirroredServer? = MirroredServer(
        sourceId = sourceId,
        name = name,
        baseUrl = baseUrl ?: return null,
        username = username ?: return null,
        priority = priority,
        formPost = serverCapabilities?.contains(FORM_POST) == true,
    )

    private companion object {
        const val FORM_POST = "\"formPost\""
        const val FORM_POST_CAPABILITIES = "[$FORM_POST]"
    }
}
