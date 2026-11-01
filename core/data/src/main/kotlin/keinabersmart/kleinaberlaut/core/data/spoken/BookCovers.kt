package keinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.repository.seedColorOf
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.datasource.local.LocalBookScanner
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.LOCAL_BOOK_SERVER_ID
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Cover der Bücher: einmal vom Server geladen, danach aus dem Cache-Verzeichnis (auch ohne Server). */
@Singleton
class BookCovers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val podcastApi: PodcastFeedApi,
    private val localScanner: LocalBookScanner,
) {
    suspend fun cover(bookId: Long): Bitmap? = withContext(Dispatchers.IO) {
        val book = dao.book(bookId) ?: return@withContext null
        // Folgen eines Podcasts teilen sich meist ein Bild: die Datei heißt nach der Adresse, nicht nach der Folge.
        val coverUrl = book.coverUrl.takeIf { book.kind == BookKind.EPISODE }
        val name = if (book.kind == BookKind.EPISODE) coverUrl?.let { "u${it.hashCode()}" } ?: return@withContext null else "$bookId"
        val file = File(File(context.cacheDir, DIRECTORY), "$name.img")
        if (!file.exists()) {
            val bytes = try {
                if (coverUrl != null) {
                    podcastApi.image(coverUrl)
                } else if (book.serverId == LOCAL_BOOK_SERVER_ID) {
                    // Lokale Bücher tragen ihr Cover in der ersten Datei.
                    dao.tracks(bookId).firstOrNull()?.let { localScanner.cover(it.remoteFileId) } ?: return@withContext null
                } else {
                    val server = servers.server(book.serverId) ?: return@withContext null
                    api.cover(server, book.remoteId, WIDTH_PX)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Cover von ${book.title} nicht abrufbar", e)
                null
            } ?: return@withContext null
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        BitmapFactory.decodeFile(file.path)
    }

    /** Das zwischengespeicherte Cover gilt nicht mehr (lokale Bücher, deren Datei sich geändert hat). */
    fun invalidate(bookId: Long) {
        File(File(context.cacheDir, DIRECTORY), "$bookId.img").delete()
        seedColors.remove(bookId)
    }

    private val seedColors = ConcurrentHashMap<Long, Int>()

    /** Grundfarbe des Covers für das Farbschema der Wiedergabe-Ansicht (wie bei Alben); nur im Speicher gemerkt. */
    suspend fun seedColor(bookId: Long): Int? {
        seedColors[bookId]?.let { return it }
        val bitmap = cover(bookId) ?: return null
        val color = withContext(Dispatchers.Default) { seedColorOf(bitmap) }
        seedColors[bookId] = color
        return color
    }

    private companion object {
        const val TAG = "BookCovers"
        const val DIRECTORY = "book_covers"
        const val WIDTH_PX = 800
    }
}
