package de.kleinabersmart.earloft.core.data.repository

import android.graphics.Bitmap
import android.util.Log
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import kotlinx.coroutines.flow.first
import org.json.JSONException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Lädt Cover unabhängig davon, aus welcher Quelle sie stammen. */
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
     * Zuerst die Cover-Datei auf dem Gerät (Uhr, Wear-Konzept 7.3; Handy: für offline vorab geladen), dann der Cache, sonst
     * die Quelle. Datei und Cache gelten nur, wenn sie mindestens [sizePx] groß sind; ist die Quelle nicht erreichbar, nimmt
     * die App auch ein kleineres Bild.
     */
    suspend fun albumCover(albumId: Long, sizePx: Int): Bitmap? {
        val file = coverFiles.read(albumId)
        if (file != null && file.longestSide >= sizePx) return file
        val cached = coverCache.read(albumId)
        if (cached != null && cached.longestSide >= sizePx) return cached
        val loaded = loadFromSource(albumId, sizePx)
        if (loaded != null) {
            coverCache.write(albumId, loaded)
            return loaded
        }
        return listOfNotNull(file, cached).maxByOrNull { it.longestSide } ?: onlineAlbumCover(albumId)
    }

    private val Bitmap.longestSide get() = maxOf(width, height)

    /** Interpreten-Bild aus dem Internet; `null`, wenn die Option aus ist oder es keines gibt. */
    suspend fun artistImage(artistId: Long): Bitmap? {
        if (!settings.artistImages.first()) return null
        val kind = OnlineImageKind.ARTIST
        onlineFiles.read(kind, artistId)?.let { return it }
        if (onlineFiles.isRecentlyNotFound(kind, artistId)) return null
        val name = libraryDao.getArtistName(artistId) ?: return null
        return fetchOnline(kind, artistId) { onlineClient.findArtistImage(name) }
    }

    /** Gleicht die Dateien mit den Einstellungen ab: ausgeschaltete Optionen räumen ihre Bilder weg. */
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
            return null // Kein Netz o. Ä.: nicht als „nicht gefunden“ merken
        } catch (e: JSONException) {
            return null
        } ?: run {
            onlineFiles.markNotFound(kind, id)
            return null
        }
        onlineFiles.write(kind, id, bytes)
        return onlineFiles.read(kind, id) ?: run {
            onlineFiles.markNotFound(kind, id) // kein lesbares Bild
            null
        }
    }

    /** Holt das Cover von der Quelle und legt es als Datei ab, falls noch keine da ist. @return `true`, wenn jetzt eine Datei da ist. */
    suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean {
        if (coverFiles.has(albumId)) return true
        val bitmap = coverCache.read(albumId)?.takeIf { it.longestSide >= sizePx } ?: loadFromSource(albumId, sizePx) ?: return false
        coverFiles.write(albumId, bitmap)
        return true
    }

    /**
     * Für offline (Handy): legt das Cover von [albumId] aus seiner Cover-Quelle [source] (`LibraryDao.getCoverSource`) als
     * Datei ab, falls noch keine da ist. @return `false` nur, wenn die Quelle nicht antwortete; ohne Cover `true`.
     */
    suspend fun keepAlbumCoverOffline(albumId: Long, source: PlayableSourceRow, sizePx: Int): Boolean {
        if (coverFiles.has(albumId)) return true
        val bitmap = coverCache.read(albumId)?.takeIf { it.longestSide >= sizePx } ?: try {
            registry[source.sourceId]?.loadArtwork(source.coverRemoteId ?: source.remoteId, sizePx)
        } catch (e: IOException) {
            Log.w(TAG, "Cover von ${source.sourceId} nicht ladbar: $e")
            return false
        } ?: return true
        coverFiles.write(albumId, bitmap)
        return true
    }

    /** Löscht die Cover-Dateien der Alben, die es nicht mehr gibt (Handy; die Uhr behält nur die ihrer Downloads). */
    suspend fun retainAlbumCoverFiles(): Int = coverFiles.retainOnly(libraryDao.getAllAlbumIds().toSet())

    private suspend fun loadFromSource(albumId: Long, sizePx: Int): Bitmap? {
        val source = libraryDao.getCoverSource(albumId) ?: return null
        return try {
            registry[source.sourceId]?.loadArtwork(source.coverRemoteId ?: source.remoteId, sizePx)
        } catch (e: IOException) {
            // Server nicht erreichbar: Ein fehlendes Cover darf nie die App beenden (Aufrufer laufen u. a. im Hauptthread).
            Log.w(TAG, "Cover von ${source.sourceId} nicht ladbar: $e")
            null
        }
    }

    private companion object {
        const val TAG = "ArtworkRepository"
    }
}
