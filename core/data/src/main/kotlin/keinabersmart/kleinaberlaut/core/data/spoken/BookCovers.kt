package keinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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

    private companion object {
        const val TAG = "BookCovers"
        const val DIRECTORY = "book_covers"
        const val WIDTH_PX = 800
    }
}
