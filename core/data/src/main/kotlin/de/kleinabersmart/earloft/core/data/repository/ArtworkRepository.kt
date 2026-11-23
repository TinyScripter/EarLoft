package de.kleinabersmart.earloft.core.data.repository

import android.graphics.Bitmap
import android.util.Log
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import kotlinx.coroutines.flow.first
import org.json.JSONException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Loads covers regardless of which source they come from. */
@Singleton
class ArtworkRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val registry: MusicDataSources,
    private val coverFiles: CoverFiles,
    private val coverCache: CoverCache,
    private val settings: ArtworkSettings,
    private val onlineFiles: OnlineImageFiles,
    private val onlineClient: OnlineArtworkClient,
) {
    /**
     * Albums whose source has only a smaller cover than requested, with its longest side: a file or cache entry of that size is
     * all there is (review2 J25). Only in memory; a process loads such a cover once more.
     */
    private val originalSides = ConcurrentHashMap<Long, Int>()

    /**
     * First the cover file on the device (watch, Wear concept 7.3; phone: preloaded for offline use), then the cache, otherwise
     * the source. File and cache only count if they are at least [sizePx] in size, or as large as the source's original; if the
     * source is unreachable, the app also takes a smaller image.
     */
    suspend fun albumCover(albumId: Long, sizePx: Int): Bitmap? {
        val file = coverFiles.read(albumId)
        if (file != null && file.suffices(albumId, sizePx)) return file
        val cached = coverCache.read(albumId)
        if (cached != null && cached.suffices(albumId, sizePx)) return cached
        val loaded = loadFromSource(albumId, sizePx)
        if (loaded != null) {
            coverCache.write(albumId, loaded)
            return loaded
        }
        return listOfNotNull(file, cached).maxByOrNull { it.longestSide } ?: onlineAlbumCover(albumId)
    }

    private val Bitmap.longestSide get() = maxOf(width, height)

    private fun Bitmap.suffices(albumId: Long, sizePx: Int) = longestSide >= minOf(sizePx, originalSides[albumId] ?: sizePx)

    /** Artist image from the internet; `null` if the option is off or there is none. */
    suspend fun artistImage(artistId: Long): Bitmap? {
        if (!settings.artistImages.first()) return null
        val kind = OnlineImageKind.ARTIST
        onlineFiles.read(kind, artistId)?.let { return it }
        if (onlineFiles.isRecentlyNotFound(kind, artistId)) return null
        val name = libraryDao.getArtistName(artistId) ?: return null
        return fetchOnline(kind, artistId) { onlineClient.findArtistImage(name) }
    }

    /** Reconciles the files with the settings: disabled options clean up their images. */
    suspend fun clearDisabledOnlineImages() {
        if (!settings.artistImages.first()) onlineFiles.clear(OnlineImageKind.ARTIST)
        if (!settings.albumCovers.first()) onlineFiles.clear(OnlineImageKind.ALBUM)
    }

    private suspend fun onlineAlbumCover(albumId: Long): Bitmap? {
        if (!settings.albumCovers.first()) return null
        val kind = OnlineImageKind.ALBUM
        onlineFiles.read(kind, albumId)?.let { return it }
        if (onlineFiles.isRecentlyNotFound(kind, albumId)) return null
        val album = libraryDao.getAlbumLookup(albumId) ?: return null
        return fetchOnline(kind, albumId) { onlineClient.findAlbumCover(album.title, album.artist, album.mbid) }
    }

    private suspend fun fetchOnline(kind: OnlineImageKind, id: Long, load: suspend () -> ByteArray?): Bitmap? {
        val bytes = try {
            load()
        } catch (e: IOException) {
            return null // No network or similar: don't remember as "not found"
        } catch (e: JSONException) {
            return null
        } ?: run {
            onlineFiles.markNotFound(kind, id)
            return null
        }
        onlineFiles.write(kind, id, bytes)
        return onlineFiles.read(kind, id) ?: run {
            onlineFiles.markNotFound(kind, id) // no readable image
            null
        }
    }

    /** Fetches the cover from the source and stores it as a file if there isn't one yet. @return `true` if there is a file now. */
    suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean {
        if (coverFiles.has(albumId)) return true
        val bitmap = coverCache.read(albumId)?.takeIf { it.suffices(albumId, sizePx) } ?: loadFromSource(albumId, sizePx) ?: return false
        coverFiles.write(albumId, bitmap)
        return true
    }

    /**
     * For offline use (phone): stores the cover of [albumId] from its cover source [source] (`LibraryDao.getCoverSource`) as a
     * file if there isn't one yet. @return `false` only if the source didn't respond; without a cover `true`.
     */
    suspend fun keepAlbumCoverOffline(albumId: Long, source: PlayableSourceRow, sizePx: Int): Boolean {
        if (coverFiles.has(albumId)) return true
        val bitmap = coverCache.read(albumId)?.takeIf { it.suffices(albumId, sizePx) } ?: try {
            registry[source.sourceId]?.loadArtwork(source.coverRemoteId ?: source.remoteId, sizePx)?.also { it.rememberSize(albumId, sizePx) }
        } catch (e: IOException) {
            Log.w(TAG, "Cover from ${source.sourceId} not loadable: $e")
            return false
        } ?: return true
        coverFiles.write(albumId, bitmap)
        return true
    }

    /** Smaller than requested: the source has no larger one. */
    private fun Bitmap.rememberSize(albumId: Long, sizePx: Int) {
        if (longestSide < sizePx) originalSides[albumId] = longestSide
    }

    /** Deletes the cover files of albums that no longer exist (phone; the watch only keeps those of its downloads). */
    suspend fun retainAlbumCoverFiles(): Int = coverFiles.retainOnly(libraryDao.getAllAlbumIds().toSet())

    private suspend fun loadFromSource(albumId: Long, sizePx: Int): Bitmap? {
        val source = libraryDao.getCoverSource(albumId) ?: return null
        return try {
            registry[source.sourceId]?.loadArtwork(source.coverRemoteId ?: source.remoteId, sizePx)?.also { it.rememberSize(albumId, sizePx) }
        } catch (e: IOException) {
            // Server unreachable: a missing cover must never crash the app (callers run on the main thread, among others).
            Log.w(TAG, "Cover from ${source.sourceId} not loadable: $e")
            null
        }
    }

    private companion object {
        const val TAG = "ArtworkRepository"
    }
}
