package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.PART_SUFFIX
import de.kleinabersmart.earloft.core.data.repository.adoptingLegacy
import de.kleinabersmart.earloft.core.data.repository.decodeScaled
import de.kleinabersmart.earloft.core.data.repository.hashedName
import de.kleinabersmart.earloft.core.data.repository.isImage
import de.kleinabersmart.earloft.core.data.repository.offlineImageDirectory
import de.kleinabersmart.earloft.core.data.repository.shrinkImage
import de.kleinabersmart.earloft.core.data.repository.writeAtomically
import de.kleinabersmart.earloft.core.data.repository.seedColorOf
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.model.BookCoverRow
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
 * Covers of books and images of episodes and subscriptions: downloaded once from the server, then served from the device (even
 * without server and without network). They are not in the cache directory so the system doesn't clear them; `OfflineImages`
 * preloads missing ones and removes orphaned ones via [retainKnown]. Large images are stored downscaled ([MAX_STORED_PX]).
 * An episode without its own image (or whose image can't be loaded) shows that of its subscription.
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

    /** The image as file content, as it was downloaded (the phone sends it to the watch, Wear concept 16.1/16.2). */
    suspend fun coverBytes(bookId: Long): ByteArray? = withContext(Dispatchers.IO) { file(bookId)?.readBytes() }

    /**
     * Adopts an image fetched elsewhere as the cover of [bookId] (the watch from the phone, if it can't download it itself).
     * [cover] then serves it from the cache.
     */
    suspend fun store(bookId: Long, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val book = dao.book(bookId) ?: return@withContext
        writeOwn(book, bytes)
    }

    /** Downloads the image into the cache if it's still missing there. @return `false` if there is none or it couldn't be loaded. */
    suspend fun ensureCached(bookId: Long): Boolean = withContext(Dispatchers.IO) { file(bookId) != null }

    /**
     * For offline: downloads the own image of [bookId] (without falling back to the subscription's) if it's still missing. @return
     * `false` only if the server or network didn't respond; `true` without an own image – then there's nothing to download.
     */
    suspend fun ensureOwnCached(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        loadOwnIfMissing(dao.book(bookId) ?: return@withContext true)
    }

    /** Does [bookId] have an own image (cached or loadable now), without the subscription's? */
    suspend fun hasOwn(bookId: Long): Boolean = withContext(Dispatchers.IO) { file(bookId, withFeedImage = false) != null }

    /**
     * Like the other [ensureOwnCached], for a list entry: if the file is already there, without reading the whole row (including
     * show notes; the offline run checks every episode of every subscription this way, review D21).
     */
    suspend fun ensureOwnCached(row: BookCoverRow): Boolean = withContext(Dispatchers.IO) {
        if (ownFile(row.bookId, episodeUrl(row.kind, row.serverId, row.coverUrl)).exists()) return@withContext true
        loadOwnIfMissing(dao.book(row.bookId) ?: return@withContext true)
    }

    private suspend fun loadOwnIfMissing(book: BookEntity): Boolean {
        val own = ownFile(book)
        if (own.exists()) return true
        return try {
            loadOwn(book)?.let { writeOwn(book, it) }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Cover of ${book.title} not available", e)
            false
        }
    }

    /**
     * Thumbnail for lists, at most [maxPx] in size, falling back to the subscription's image as with [cover]. Without [load] only from
     * the cache, without server (a long list on the watch shouldn't download for every entry).
     */
    suspend fun thumbnail(bookId: Long, maxPx: Int, load: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        file(bookId, load = load)?.let { decodeScaled(it, maxPx) }
    }

    /** A subscription's image, at most [maxPx] in size (podcast list on the watch); for podcasts from the device an episode's cover. */
    suspend fun feedThumbnail(feedId: String, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        feedImageFile(feedId)?.let { decodeScaled(it, maxPx) }
    }

    /** Downloads the image of subscription [feedId] to the device if it's still missing there. @return `false` if there is none or it couldn't be loaded. */
    suspend fun ensureFeedImageCached(feedId: String): Boolean = withContext(Dispatchers.IO) { feedImageFile(feedId) != null }

    /**
     * The stored file for the image address [url] of a subscription or episode, without downloading; `null` if it's missing. The
     * UI shows subscription images via their address and thus uses this file when offline.
     */
    fun storedImage(url: String): File? = urlFile(url).takeIf { it.isFile }

    /**
     * Deletes the images of books, episodes and subscriptions that no longer exist (hidden ones and trial subscriptions keep theirs).
     * @return number of deleted files.
     */
    suspend fun retainKnown(): Int = withContext(Dispatchers.IO) {
        val keep = HashSet<String>()
        dao.coverRows().forEach {
            keep += ownFile(it.bookId, episodeUrl(it.kind, it.serverId, it.coverUrl)).name
            keep += staleFile(it.bookId).name
        }
        podcastDao.allImageUrls().filter { it.startsWith("http") }.forEach { keep += urlFile(it).name }
        // `.part` files are currently being written by someone.
        directory.listFiles()?.count { it.name !in keep && !it.name.endsWith(PART_SUFFIX) && it.delete() } ?: 0
    }

    private suspend fun feedImageFile(feedId: String): File? {
        val feed = podcastDao.feed(feedId) ?: return null
        val image = feed.imageUrl ?: return null
        return imageFile(image, ownerId = null, load = true, label = feed.title)
    }

    /** The image file of [bookId]; without [load] only what is already in the cache. */
    private suspend fun file(bookId: Long, withFeedImage: Boolean = true, load: Boolean = true): File? {
        val book = dao.book(bookId) ?: return null
        val own = ownFile(book)
        if (own.exists() || (load && fetchOwn(book))) return own
        // A replaced cover that can't be loaded right now (offline): the previous one rather than none (review4 8.6 Y11).
        staleFile(bookId).takeIf { book.episodeUrl == null && it.exists() }?.let { return it }
        if (!withFeedImage) return null
        val image = feedImage(book) ?: return null
        return imageFile(image, ownerId = bookId, load = load, label = book.title)
    }

    /**
     * The file for a subscription's image ([image] = its `imageUrl`): subscriptions from the device use the cover of one of their
     * episodes as image (except [ownerId] itself, otherwise it would go in circles), others an address.
     */
    private suspend fun imageFile(image: String, ownerId: Long?, load: Boolean, label: String): File? {
        AppUris.parseBookCoverId(image)?.let { return if (it != ownerId) file(it, withFeedImage = false, load = load) else null }
        if (!image.startsWith("http")) return null
        val file = urlFile(image)
        if (file.exists()) return file
        if (!load) return null
        val bytes = fetch(label) { podcastApi.image(image) } ?: return null
        return file.takeIf { write(it, bytes) }
    }

    /** The image of an episode's subscription, if it isn't already the episode's own. */
    private suspend fun feedImage(book: BookEntity): String? {
        if (book.kind != BookKind.EPISODE) return null
        return podcastDao.feed(book.serverId)?.imageUrl?.takeIf { it != book.episodeUrl }
    }

    /** Downloads the own image of [book]. @return `false` if there is none or it couldn't be loaded. */
    private suspend fun fetchOwn(book: BookEntity): Boolean {
        val bytes = fetch(book.title) { loadOwn(book) } ?: return false
        return writeOwn(book, bytes)
    }

    /** Stores [bytes] as the own image of [book]; a replaced previous one is no longer needed then. */
    private fun writeOwn(book: BookEntity, bytes: ByteArray): Boolean =
        write(ownFile(book), bytes).also { if (it) staleFile(book.bookId).delete() }

    /** The own image of [book]; `null` if there is none. @throws IOException if it couldn't be loaded. */
    private suspend fun loadOwn(book: BookEntity): ByteArray? {
        val url = book.episodeUrl
        return if (url != null) {
            podcastApi.image(url)
        } else if (isLocalSource(book.serverId)) {
            // Local books carry their cover in the folder (with folder access, `books_improvements.md` M7) or in the first
            // file, episodes only in the file. On the watch this is a phone identifier, not a file: there the image comes via [store].
            book.coverUrl?.takeIf { book.kind == BookKind.BOOK && it.startsWith("content:") }?.let { localScanner.folderCover(it) }
                ?: dao.tracks(book.bookId).firstOrNull()?.remoteFileId?.takeIf { it.startsWith("content:") }?.let { localScanner.cover(it) }
        } else if (book.kind == BookKind.EPISODE) {
            null
        } else if (isArdSoundsBook(book.serverId)) {
            // Books from ARD Sounds carry the image address from there.
            book.coverUrl?.takeIf { it.startsWith("http") }?.let { podcastApi.image(it) }
        } else {
            servers.server(book.serverId)?.let { api.cover(it, book.remoteId, WIDTH_PX) }
        }
    }

    private suspend fun fetch(label: String, load: suspend () -> ByteArray?): ByteArray? = try {
        load()
    } catch (e: IOException) {
        Log.w(TAG, "Cover of $label not available", e)
        null
    }

    private fun ownFile(book: BookEntity): File = ownFile(book.bookId, book.episodeUrl)

    /** Episodes of a podcast usually share an image: the file is then named after the address, not the episode. */
    private fun ownFile(bookId: Long, episodeUrl: String?): File = episodeUrl?.let(::urlFile) ?: File(directory, "$bookId.img")

    /** The previous cover of [bookId] after [invalidate], until the new one is loaded. */
    private fun staleFile(bookId: Long): File = File(directory, "$bookId.old")

    /** The file used to be named after `hashCode`; [retainKnown] thus moves all known ones once and clears away the rest. */
    private fun urlFile(url: String) = File(directory, "u${hashedName(url)}.img").adoptingLegacy(File(directory, "u${url.hashCode()}.img"))

    private val directory by lazy { offlineImageDirectory(context, DIRECTORY) }

    /**
     * Stores [bytes] only if they are an image: an error page or a broken answer would otherwise stay as the cover for good,
     * since an existing file is never loaded again (review H14). @return whether it was stored.
     */
    private fun write(file: File, bytes: ByteArray): Boolean {
        if (!isImage(bytes)) {
            Log.w(TAG, "Not an image (${bytes.size} bytes), not stored as ${file.name}")
            return false
        }
        writeAtomically(file, shrinkImage(bytes, MAX_STORED_PX))
        return true
    }

    private val BookEntity.episodeUrl: String? get() = episodeUrl(kind, serverId, coverUrl)

    /** The image address of an episode from a feed; episodes from the device carry their image in the file. */
    private fun episodeUrl(kind: BookKind, serverId: String, coverUrl: String?): String? =
        coverUrl.takeIf { kind == BookKind.EPISODE && !isLocalSource(serverId) }

    /**
     * The cached cover is no longer valid (book changed on the server, local books whose file has changed): it is loaded again on the
     * next display. Until that succeeds, the previous one is still shown ([staleFile]), e.g. offline (review4 8.6 Y11).
     */
    fun invalidate(bookId: Long) {
        val own = File(directory, "$bookId.img")
        if (own.exists()) {
            val stale = staleFile(bookId)
            stale.delete()
            if (!own.renameTo(stale)) own.delete()
        }
        seedColors.remove(bookId)
    }

    private val seedColors = ConcurrentHashMap<Long, Int>()

    /** Base color of the cover for the now playing color scheme (like with albums); only kept in memory. */
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

        /** The app shows covers at most this large (now playing view); larger ones only cost space. */
        const val MAX_STORED_PX = 800
    }
}
