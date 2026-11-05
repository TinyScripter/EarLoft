package keinabersmart.kleinaberlaut.wear.ui

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
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Woher das Vorschaubild eines Listeneintrags kommt. */
sealed interface CoverRef {
    data class Album(val albumId: Long) : CoverRef

    /** Buch oder Folge; ohne eigenes Bild das des Abos. */
    data class Book(val bookId: Long) : CoverRef
}

/**
 * Vorschaubilder für die Listen der Uhr: klein aus den Dateien, die mit den Downloads kommen (Album-Cover, Bilder der Bücher
 * und Folgen), ohne Server. Die zuletzt gezeigten bleiben im Speicher, damit Scrollen nicht jedes Mal neu dekodiert.
 */
@Singleton
class ListCovers @Inject constructor(
    private val coverFiles: CoverFiles,
    private val bookCovers: BookCovers,
) {
    private val cache = LruCache<CoverRef, ImageBitmap>(CACHE_ENTRIES)

    /** Liegt das Bild schon im Speicher? Dann zeigt die Liste es ohne Platzhalter. */
    fun cached(ref: CoverRef): ImageBitmap? = cache.get(ref)

    suspend fun load(ref: CoverRef): ImageBitmap? {
        cache.get(ref)?.let { return it }
        val bitmap = when (ref) {
            is CoverRef.Album -> withContext(Dispatchers.IO) { coverFiles.readScaled(ref.albumId, THUMBNAIL_PX) }
            is CoverRef.Book -> bookCovers.thumbnail(ref.bookId, THUMBNAIL_PX)
        } ?: return null
        return bitmap.asImageBitmap().also { cache.put(ref, it) }
    }

    private companion object {
        /** Gut doppelt so groß wie das Bild in der Liste (40 dp), bei rund 2 px je dp. */
        const val THUMBNAIL_PX = 96
        /** Je Bild rund 37 KB: zusammen unter 3 MB. */
        const val CACHE_ENTRIES = 80
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
