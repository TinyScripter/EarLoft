package keinabersmart.kleinaberlaut.wear.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.LocalContentColor
import keinabersmart.kleinaberlaut.core.data.repository.CoverFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import keinabersmart.kleinaberlaut.wear.download.PhoneFileFetcher
import keinabersmart.kleinaberlaut.wear.sync.PhoneConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Woher das Vorschaubild eines Listeneintrags kommt. */
sealed interface CoverRef {
    data class Album(val albumId: Long) : CoverRef

    /** Buch oder Folge; ohne eigenes Bild das des Abos. */
    data class Book(val bookId: Long) : CoverRef

    /** Ein Album des Handys ([albumId] = ID des Handys), Bereich „Telefon“ (Wear-Konzept 17). */
    data class PhoneAlbum(val albumId: Long) : CoverRef
}

/**
 * Vorschaubilder für die Listen der Uhr: klein aus den Dateien, die mit den Downloads kommen (Album-Cover, Bilder der Bücher
 * und Folgen), ohne Server. Die zuletzt gezeigten bleiben im Speicher, damit Scrollen nicht jedes Mal neu dekodiert.
 *
 * Bilder von Alben des Handys holt die Uhr erst, wenn der Eintrag sichtbar wird, klein über den Datei-Kanal
 * ([PhoneFileFetcher.fetchAlbumThumbnail]) und legt sie im Cache-Verzeichnis ab; höchstens [PHONE_PARALLEL] gleichzeitig,
 * damit schnelles Scrollen die Verbindung nicht verstopft. Was das Handy nicht liefert, fragt sie bis zum Neustart nicht
 * noch einmal.
 */
@Singleton
class ListCovers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val coverFiles: CoverFiles,
    private val bookCovers: BookCovers,
    private val phoneFiles: PhoneFileFetcher,
    private val phone: PhoneConnection,
) {
    private val cache = LruCache<CoverRef, ImageBitmap>(CACHE_ENTRIES)
    private val phoneRequests = Semaphore(PHONE_PARALLEL)
    private val phoneMissing = ConcurrentHashMap.newKeySet<Long>()

    /** Liegt das Bild schon im Speicher? Dann zeigt die Liste es ohne Platzhalter. */
    fun cached(ref: CoverRef): ImageBitmap? = cache.get(ref)

    suspend fun load(ref: CoverRef): ImageBitmap? {
        cache.get(ref)?.let { return it }
        val bitmap = when (ref) {
            is CoverRef.Album -> withContext(Dispatchers.IO) { coverFiles.readScaled(ref.albumId, THUMBNAIL_PX) }
            is CoverRef.Book -> bookCovers.thumbnail(ref.bookId, THUMBNAIL_PX)
            is CoverRef.PhoneAlbum -> phoneAlbum(ref.albumId)
        } ?: return null
        return bitmap.asImageBitmap().also { cache.put(ref, it) }
    }

    private suspend fun phoneAlbum(albumId: Long): Bitmap? {
        val file = File(context.cacheDir, "$PHONE_DIRECTORY/$albumId.jpg")
        if (!file.isFile) {
            if (albumId in phoneMissing) return null
            // Ohne Handy nichts als „fehlt“ merken: Nach dem Verbinden soll das Bild kommen.
            phone.connectedPhoneNodeId() ?: return null
            val bytes = phoneRequests.withPermit { phoneFiles.fetchAlbumThumbnail(albumId) }
            if (bytes == null) {
                phoneMissing += albumId
                return null
            }
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        }
        return withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }
    }

    private companion object {
        /** Gut doppelt so groß wie das Bild in der Liste (40 dp), bei rund 2 px je dp. */
        const val THUMBNAIL_PX = 96
        /** Je Bild rund 37 KB (vom Handy 120 px: rund 58 KB): zusammen unter 5 MB. */
        const val CACHE_ENTRIES = 80
        const val PHONE_PARALLEL = 2
        const val PHONE_DIRECTORY = "phone_thumbnails"
    }
}

/** In der App von der Activity gesetzt; in Vorschauen fehlt sie, dann bleibt es beim Platzhalter. */
val LocalListCovers = staticCompositionLocalOf<ListCovers?> { null }

private val THUMBNAIL_SIZE = 40.dp
private val THUMBNAIL_SHAPE = RoundedCornerShape(8.dp)
private const val PLACEHOLDER_ALPHA = 0.15f

/** Vorschaubild eines Listeneintrags; bis es geladen ist und wenn es keins gibt, eine leere Fläche. */
@Composable
fun CoverThumbnail(ref: CoverRef, modifier: Modifier = Modifier) {
    val covers = LocalListCovers.current
    val image by produceState(covers?.cached(ref), ref, covers) {
        if (value == null && covers != null) value = covers.load(ref)
    }
    val shaped = modifier.size(THUMBNAIL_SIZE).clip(THUMBNAIL_SHAPE)
    val loaded = image
    if (loaded != null) {
        Image(loaded, contentDescription = null, modifier = shaped, contentScale = ContentScale.Crop)
    } else {
        // Leicht abgesetzt von der Farbe des Knopfs, egal welche das ist.
        Box(shaped.background(LocalContentColor.current.copy(alpha = PLACEHOLDER_ALPHA)))
    }
}
