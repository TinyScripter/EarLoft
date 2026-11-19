package de.kleinabersmart.earloft.core.datasource

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decodes an image so that it stays at least [sizePx] in size, but doesn't get unnecessarily large. */
internal fun decodeSampled(bytes: ByteArray, sizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= sizePx && bounds.outHeight / (sampleSize * 2) >= sizePx) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
