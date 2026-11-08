package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.repository.ArtworkSettings
import de.kleinabersmart.earloft.core.data.repository.OnlineArtworkClient
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsAuthorDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Autorenbilder: zuerst die von Audiobookshelf, dann (nur bei eingeschalteter Option) aus dem Internet (Audnexus).
 * `null`, wenn es keines gibt – die Oberfläche nimmt dann das Cover eines Buchs. Gefundene Bilder liegen im
 * Cache-Verzeichnis; Bilder aus dem Internet verschwinden, wenn die Option ausgeschaltet wird.
 */
@Singleton
class AuthorImages @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val settings: ArtworkSettings,
    private val onlineClient: OnlineArtworkClient,
) {
    private val indexLock = Mutex()
    private val indexes = HashMap<String, Pair<Long, Map<String, AbsAuthorDto>>>()

    suspend fun image(name: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = name.normalizeForMerge()
        if (key.isEmpty()) return@withContext null
        val candidates = candidates(name)
        val serverFile = File(directory(SERVER), fileName(key))
        decode(serverFile)?.let { return@withContext it }
        fromServers(candidates)?.let { bytes ->
            serverFile.parentFile?.mkdirs()
            serverFile.writeBytes(bytes)
            decode(serverFile)?.let { return@withContext it }
            serverFile.delete()
        }
        if (!settings.authorImages.first()) return@withContext null
        val onlineFile = File(directory(ONLINE), fileName(key))
        decode(onlineFile)?.let { return@withContext it }
        val marker = File(directory(ONLINE), fileName(key) + ".none")
        if (marker.exists() && System.currentTimeMillis() - marker.lastModified() < NOT_FOUND_MS) return@withContext null
        val bytes = try {
            candidates.firstNotNullOfOrNull { onlineClient.findAuthorImage(it) }
        } catch (e: IOException) {
            return@withContext null // Kein Netz o. Ä.: nicht als „nicht gefunden“ merken
        } catch (e: JSONException) {
            return@withContext null
        }
        onlineFile.parentFile?.mkdirs()
        if (bytes == null) {
            marker.createNewFile()
            marker.setLastModified(System.currentTimeMillis())
            return@withContext null
        }
        onlineFile.writeBytes(bytes)
        decode(onlineFile) ?: run {
            onlineFile.delete()
            marker.createNewFile()
            null
        }
    }

    /** Räumt die Bilder aus dem Internet weg (Option ausgeschaltet). */
    suspend fun clearOnlineImages() = withContext(Dispatchers.IO) { directory(ONLINE).deleteRecursively() }

    /** Der ganze Name, bei mehreren Autoren („A, B“, „A & B“, „A und B“) zusätzlich der erste. */
    private fun candidates(name: String): List<String> {
        val first = name.split(SEPARATORS).firstOrNull()?.trim().orEmpty()
        return listOfNotNull(name.trim(), first.takeIf { it.isNotEmpty() && it != name.trim() })
    }

    private suspend fun fromServers(candidates: List<String>): ByteArray? {
        val wanted = candidates.map { it.normalizeForMerge() }
        for (entity in dao.servers()) {
            val libraryId = entity.libraryId ?: continue
            val server = servers.server(entity.serverId) ?: continue
            val index = try {
                index(entity.serverId) { api.authors(server, libraryId) }
            } catch (e: IOException) {
                Log.w(TAG, "Autoren von ${entity.name} nicht abrufbar", e)
                continue
            }
            val author = wanted.firstNotNullOfOrNull { index[it] }?.takeIf { it.imagePath != null } ?: continue
            try {
                api.authorImage(server, author.id, WIDTH_PX)?.let { return it }
            } catch (e: IOException) {
                Log.w(TAG, "Autorenbild von ${author.name} nicht abrufbar", e)
            }
        }
        return null
    }

    private suspend fun index(serverId: String, load: suspend () -> List<AbsAuthorDto>): Map<String, AbsAuthorDto> =
        indexLock.withLock {
            val cached = indexes[serverId]
            if (cached != null && System.currentTimeMillis() - cached.first < INDEX_TTL_MS) return@withLock cached.second
            val map = load().associateBy { it.name.normalizeForMerge() }
            indexes[serverId] = System.currentTimeMillis() to map
            map
        }

    private fun decode(file: File): Bitmap? = if (file.isFile) BitmapFactory.decodeFile(file.path) else null

    private fun directory(kind: String) = File(File(context.cacheDir, DIRECTORY), kind)

    private fun fileName(key: String) = "${key.hashCode()}.img"

    private companion object {
        const val TAG = "AuthorImages"
        const val DIRECTORY = "author_images"
        const val SERVER = "server"
        const val ONLINE = "online"
        const val WIDTH_PX = 600
        const val NOT_FOUND_MS = 7 * 24 * 60 * 60 * 1000L
        const val INDEX_TTL_MS = 10 * 60 * 1000L
        val SEPARATORS = Regex(",| & | und | and ")
    }
}
