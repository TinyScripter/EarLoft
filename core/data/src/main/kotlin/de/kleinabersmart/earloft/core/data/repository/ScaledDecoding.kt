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
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= maxPx) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val decoded = maxOf(bitmap.width, bitmap.height)
    if (decoded <= maxPx) return bitmap
    val factor = maxPx.toFloat() / decoded
    return bitmap.scale((bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1))
}
