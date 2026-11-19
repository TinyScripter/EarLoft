package de.kleinabersmart.earloft.core.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File

/**
 * Reads an image at most [maxPx] in size (longest side): first coarsely while decoding, then scaled exactly. For thumbnails in
 * lists, so a large cover doesn't end up in memory at full size. `null` if the file is missing or isn't an image.
 */
internal fun decodeScaled(file: File, maxPx: Int): Bitmap? {
    if (!file.isFile) return null
    return decodeScaled(maxPx) { options -> BitmapFactory.decodeFile(file.path, options) }
}

/** As above, from the bytes of an image. */
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
