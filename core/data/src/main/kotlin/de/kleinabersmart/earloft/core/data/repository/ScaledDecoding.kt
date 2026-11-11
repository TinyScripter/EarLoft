package de.kleinabersmart.earloft.core.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File

/**
 * Liest ein Bild höchstens [maxPx] groß (längste Seite): erst grob beim Dekodieren, dann genau skaliert. Für Vorschaubilder in
 * Listen, damit ein großes Cover nicht in voller Größe im Speicher landet. `null`, wenn die Datei fehlt oder kein Bild ist.
 */
internal fun decodeScaled(file: File, maxPx: Int): Bitmap? {
    if (!file.isFile) return null
    return decodeScaled(maxPx) { options -> BitmapFactory.decodeFile(file.path, options) }
}

/** Wie oben, aus den Bytes eines Bildes. */
internal fun decodeScaled(bytes: ByteArray, maxPx: Int): Bitmap? =
    decodeScaled(maxPx) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }

private fun decodeScaled(maxPx: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= maxPx) sample *= 2
    val bitmap = decode(BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val decoded = maxOf(bitmap.width, bitmap.height)
    if (decoded <= maxPx) return bitmap
    val factor = maxPx.toFloat() / decoded
    return bitmap.scale((bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1))
}
