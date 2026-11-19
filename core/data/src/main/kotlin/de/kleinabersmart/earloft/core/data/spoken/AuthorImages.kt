package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.repository.ArtworkSettings
import de.kleinabersmart.earloft.core.data.repository.OnlineArtworkClient
import de.kleinabersmart.earloft.core.data.repository.adoptingLegacy
import de.kleinabersmart.earloft.core.data.repository.hashedName
import de.kleinabersmart.earloft.core.data.repository.offlineImageDirectory
import de.kleinabersmart.earloft.core.data.repository.shrinkImage
import de.kleinabersmart.earloft.core.data.repository.writeAtomically
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
 * Author images: first those from Audiobookshelf, then (only with the option enabled) from the internet (Audnexus).
 * `null` if there is none – the UI then uses a book's cover. Found images are stored on the device (not
 * in the cache directory, `OfflineImages` preloads them); images from the internet disappear when the option is disabled.
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
        val serverFile = file(SERVER, key)
        decode(serverFile)?.let { return@withContext it }
        fromServers(candidates)?.let { bytes ->
            writeAtomically(serverFile, bytes)
            decode(serverFile)?.let { return@withContext it }
            serverFile.delete()
        }
        if (!settings.authorImages.first()) return@withContext null
        val onlineFile = file(ONLINE, key)
        decode(onlineFile)?.let { return@withContext it }
        val marker = file(ONLINE, key, NOT_FOUND_SUFFIX)
        if (marker.exists() && System.currentTimeMillis() - marker.lastModified() < NOT_FOUND_MS) return@withContext null
        val bytes = try {
            candidates.firstNotNullOfOrNull { onlineClient.findAuthorImage(it) }
        } catch (e: IOException) {
            return@withContext null // No network or similar: don't remember as "not found"
        } catch (e: JSONException) {
            return@withContext null
        }
        onlineFile.parentFile?.mkdirs()
        if (bytes == null) {
            marker.createNewFile()
            marker.setLastModified(System.currentTimeMillis())
            return@withContext null
        }
        writeAtomically(onlineFile, shrinkImage(bytes, MAX_STORED_PX))
        decode(onlineFile) ?: run {
            onlineFile.delete()
            marker.createNewFile()
            null
        }
    }

    /** Clears away the images from the internet (option disabled). */
    suspend fun clearOnlineImages() = withContext(Dispatchers.IO) { directory(ONLINE).deleteRecursively() }

    /** The full name, and with several authors ("A, B", "A & B", "A und B") additionally the first one. */
    private fun candidates(name: String): List<String> {
        val first = name.split(SEPARATORS).firstOrNull()?.trim().orEmpty()
        return listOfNotNull(name.trim(), first.takeIf { it.isNotEmpty() && it != name.trim() })
    }

    private suspend fun fromServers(candidates: List<String>): ByteArray? {
        val wanted = candidates.map { it.normalizeForMerge() }
        for (entity in dao.servers()) {
            // All visible book libraries of the server (M3); before the first sync only the one known library.
            val libraryIds = dao.libraries(entity.serverId).map { it.libraryId }.filter { it !in entity.hiddenLibraryIds }
                .ifEmpty { listOfNotNull(entity.libraryId) }
            if (libraryIds.isEmpty()) continue
            val server = servers.server(entity.serverId) ?: continue
            val index = try {
                index(entity.serverId) { libraryIds.flatMap { api.authors(server, it) } }
            } catch (e: IOException) {
                Log.w(TAG, "Authors of ${entity.name} not available", e)
                continue
            }
            val author = wanted.firstNotNullOfOrNull { index[it] }?.takeIf { it.imagePath != null } ?: continue
            try {
                api.authorImage(server, author.id, WIDTH_PX)?.let { return it }
            } catch (e: IOException) {
                Log.w(TAG, "Author image of ${author.name} not available", e)
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

    private val root by lazy { offlineImageDirectory(context, DIRECTORY) }

    private fun directory(kind: String) = File(root, kind)

    /** The file used to be named after `hashCode` (review D35); an image under the old name is moved on first access. */
    private fun file(kind: String, key: String, suffix: String = "") =
        File(directory(kind), "${hashedName(key)}.img$suffix").adoptingLegacy(File(directory(kind), "${key.hashCode()}.img$suffix"))

    private companion object {
        const val TAG = "AuthorImages"
        const val DIRECTORY = "author_images"
        const val SERVER = "server"
        const val ONLINE = "online"
        const val NOT_FOUND_SUFFIX = ".none"
        const val WIDTH_PX = 600
        const val MAX_STORED_PX = 800
        const val NOT_FOUND_MS = 7 * 24 * 60 * 60 * 1000L
        const val INDEX_TTL_MS = 10 * 60 * 1000L
        val SEPARATORS = Regex(",| & | und | and ")
    }
}
