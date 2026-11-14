package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.PART_SUFFIX
import de.kleinabersmart.earloft.core.data.repository.decodeScaled
import de.kleinabersmart.earloft.core.data.repository.offlineImageDirectory
import de.kleinabersmart.earloft.core.data.repository.shrinkImage
import de.kleinabersmart.earloft.core.data.repository.writeAtomically
import de.kleinabersmart.earloft.core.data.repository.seedColorOf
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.datasource.local.LocalBookScanner
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.isArdSoundsBook
import de.kleinabersmart.earloft.core.model.isLocalSource
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover der Bücher und Bilder der Folgen und Abos: einmal vom Server geladen, danach vom Gerät (auch ohne Server und ohne
 * Netz). Sie liegen nicht im Cache-Verzeichnis, damit das System sie nicht räumt; `OfflineImages` lädt fehlende vorab und
 * entfernt mit [retainKnown] die verwaisten. Große Bilder werden verkleinert abgelegt ([MAX_STORED_PX]).
 * Eine Folge ohne eigenes Bild (oder deren Bild sich nicht laden lässt) zeigt das ihres Abos.
 */
@Singleton
class BookCovers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val podcastDao: PodcastDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val podcastApi: PodcastFeedApi,
    private val localScanner: LocalBookScanner,
) {
    suspend fun cover(bookId: Long): Bitmap? = withContext(Dispatchers.IO) {
        file(bookId)?.let { BitmapFactory.decodeFile(it.path) }
    }

    /** Das Bild als Datei-Inhalt, wie es geladen wurde (das Handy schickt es der Uhr, Wear-Konzept 16.1/16.2). */
    suspend fun coverBytes(bookId: Long): ByteArray? = withContext(Dispatchers.IO) { file(bookId)?.readBytes() }

    /**
     * Übernimmt ein anderswo geholtes Bild als Cover von [bookId] (die Uhr vom Handy, wenn sie es nicht selbst laden kann).
     * [cover] liefert es danach aus dem Cache.
     */
    suspend fun store(bookId: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val book = dao.book(bookId) ?: return@withContext
        write(ownFile(book), bytes)
    }

    /** Lädt das Bild in den Cache, falls es dort noch fehlt. @return `false`, wenn es keins gibt oder es sich nicht laden ließ. */
    suspend fun ensureCached(bookId: Long): Boolean = withContext(Dispatchers.IO) { file(bookId) != null }

    /**
     * Für offline: lädt das eigene Bild von [bookId] (ohne Rückfall auf das des Abos), falls es noch fehlt. @return `false` nur,
     * wenn Server oder Netz nicht antworteten; ohne eigenes Bild `true` – dann gibt es nichts zu laden.
     */
    suspend fun ensureOwnCached(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        val book = dao.book(bookId) ?: return@withContext true
        val own = ownFile(book)
        if (own.exists()) return@withContext true
        try {
            loadOwn(book)?.let { write(own, it) }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Cover von ${book.title} nicht abrufbar", e)
            false
        }
    }

    /**
     * Vorschaubild für Listen, höchstens [maxPx] groß, mit Rückfall auf das Bild des Abos wie bei [cover]. Ohne [load] nur aus
     * dem Cache, ohne Server (eine lange Liste auf der Uhr soll nicht für jeden Eintrag laden).
     */
    suspend fun thumbnail(bookId: Long, maxPx: Int, load: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        file(bookId, load = load)?.let { decodeScaled(it, maxPx) }
    }

    /** Das Bild eines Abos, höchstens [maxPx] groß (Liste der Podcasts auf der Uhr); bei Podcasts vom Gerät das Cover einer Folge. */
    suspend fun feedThumbnail(feedId: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        feedImageFile(feedId)?.let { decodeScaled(it, maxPx) }
    }

    /** Lädt das Bild des Abos [feedId] auf das Gerät, falls es dort noch fehlt. @return `false`, wenn es keins gibt oder es sich nicht laden ließ. */
    suspend fun ensureFeedImageCached(feedId: String): Boolean = withContext(Dispatchers.IO) { feedImageFile(feedId) != null }

    /**
     * Die abgelegte Datei zur Bild-Adresse [url] eines Abos oder einer Folge, ohne zu laden; `null`, wenn sie fehlt. Die
     * Oberfläche zeigt Abo-Bilder über ihre Adresse und nimmt damit offline diese Datei.
     */
    fun storedImage(url: String): File? = urlFile(url).takeIf { it.isFile }

    /**
     * Löscht die Bilder von Büchern, Folgen und Abos, die es nicht mehr gibt (Ausgeblendete und Probeabos behalten ihre).
     * @return Anzahl der gelöschten Dateien.
     */
    suspend fun retainKnown(): Int = withContext(Dispatchers.IO) {
        val keep = HashSet<String>()
        dao.coverRows().forEach { keep += ownFile(it.bookId, episodeUrl(it.kind, it.serverId, it.coverUrl)).name }
        podcastDao.allImageUrls().filter { it.startsWith("http") }.forEach { keep += urlFile(it).name }
        // `.part`-Dateien schreibt gerade jemand.
        directory.listFiles()?.count { it.name !in keep && !it.name.endsWith(PART_SUFFIX) && it.delete() } ?: 0
    }

    private suspend fun feedImageFile(feedId: String): File? {
        val feed = podcastDao.feed(feedId) ?: return null
        val image = feed.imageUrl ?: return null
        return imageFile(image, ownerId = null, load = true, label = feed.title)
    }

    /** Die Bilddatei von [bookId]; ohne [load] nur, was schon im Cache liegt. */
    private suspend fun file(bookId: Long, withFeedImage: Boolean = true, load: Boolean = true): File? {
        val book = dao.book(bookId) ?: return null
        val own = ownFile(book)
        if (own.exists() || (load && fetchOwn(book, own))) return own
        if (!withFeedImage) return null
        val image = feedImage(book) ?: return null
        return imageFile(image, ownerId = bookId, load = load, label = book.title)
    }

    /**
     * Die Datei zum Bild eines Abos ([image] = dessen `imageUrl`): Abos vom Gerät haben als Bild das Cover einer ihrer Folgen
     * (außer [ownerId] selbst, sonst ginge es im Kreis), andere eine Adresse.
     */
    private suspend fun imageFile(image: String, ownerId: Long?, load: Boolean, label: String): File? {
        AppUris.parseBookCoverId(image)?.let { return if (it != ownerId) file(it, withFeedImage = false, load = load) else null }
        if (!image.startsWith("http")) return null
        val file = urlFile(image)
        if (file.exists()) return file
        if (!load) return null
        val bytes = fetch(label) { podcastApi.image(image) } ?: return null
        write(file, bytes)
        return file
    }

    /** Das Bild des Abos einer Folge, wenn es nicht schon ihr eigenes ist. */
    private suspend fun feedImage(book: BookEntity): String? {
        if (book.kind != BookKind.EPISODE) return null
        return podcastDao.feed(book.serverId)?.imageUrl?.takeIf { it != book.episodeUrl }
    }

    /** Lädt das eigene Bild von [book] nach [file]. @return `false`, wenn es keins gibt oder es sich nicht laden ließ. */
    private suspend fun fetchOwn(book: BookEntity, file: File): Boolean {
        val bytes = fetch(book.title) { loadOwn(book) } ?: return false
        write(file, bytes)
        return true
    }

    /** Das eigene Bild von [book]; `null`, wenn es keins gibt. @throws IOException, wenn es sich nicht laden ließ. */
    private suspend fun loadOwn(book: BookEntity): ByteArray? {
        val url = book.episodeUrl
        return if (url != null) {
            podcastApi.image(url)
        } else if (isLocalSource(book.serverId)) {
            // Lokale Bücher tragen ihr Cover im Ordner (mit Ordnerfreigabe, `books_improvements.md` M7) oder in der ersten
            // Datei, Folgen nur in der Datei. Auf der Uhr ist das eine Kennung des Handys, keine Datei: Dort kommt das Bild über [store].
            book.coverUrl?.takeIf { book.kind == BookKind.BOOK && it.startsWith("content:") }?.let { localScanner.folderCover(it) }
                ?: dao.tracks(book.bookId).firstOrNull()?.remoteFileId?.takeIf { it.startsWith("content:") }?.let { localScanner.cover(it) }
        } else if (book.kind == BookKind.EPISODE) {
            null
        } else if (isArdSoundsBook(book.serverId)) {
            // Bücher aus ARD Sounds tragen die Bild-Adresse von dort.
            book.coverUrl?.takeIf { it.startsWith("http") }?.let { podcastApi.image(it) }
        } else {
            servers.server(book.serverId)?.let { api.cover(it, book.remoteId, WIDTH_PX) }
        }
    }

    private suspend fun fetch(label: String, load: suspend () -> ByteArray?): ByteArray? = try {
        load()
    } catch (e: IOException) {
        Log.w(TAG, "Cover von $label nicht abrufbar", e)
        null
    }

    private fun ownFile(book: BookEntity): File = ownFile(book.bookId, book.episodeUrl)

    /** Folgen eines Podcasts teilen sich meist ein Bild: die Datei heißt dann nach der Adresse, nicht nach der Folge. */
    private fun ownFile(bookId: Long, episodeUrl: String?): File = episodeUrl?.let(::urlFile) ?: File(directory, "$bookId.img")

    private fun urlFile(url: String) = File(directory, "u${url.hashCode()}.img")

    private val directory by lazy { offlineImageDirectory(context, DIRECTORY) }

    private fun write(file: File, bytes: ByteArray) = writeAtomically(file, shrinkImage(bytes, MAX_STORED_PX))

    private val BookEntity.episodeUrl: String? get() = episodeUrl(kind, serverId, coverUrl)

    /** Die Bild-Adresse einer Folge aus einem Feed; Folgen vom Gerät tragen ihr Bild in der Datei. */
    private fun episodeUrl(kind: BookKind, serverId: String, coverUrl: String?): String? =
        coverUrl.takeIf { kind == BookKind.EPISODE && !isLocalSource(serverId) }

    /** Das zwischengespeicherte Cover gilt nicht mehr (lokale Bücher, deren Datei sich geändert hat). */
    fun invalidate(bookId: Long) {
        File(directory, "$bookId.img").delete()
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

        /** Die App zeigt Cover höchstens so groß (Wiedergabe-Ansicht); größere kosten nur Platz. */
        const val MAX_STORED_PX = 800
    }
}
