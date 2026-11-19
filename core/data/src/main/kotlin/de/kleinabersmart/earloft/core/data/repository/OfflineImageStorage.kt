package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Directory for images that should be available offline too: not in the cache directory (the system may clear it) and not
 * in the backup (they can be downloaded again). If they were previously in the cache under the same name, they are moved once.
 */
internal fun offlineImageDirectory(context: Context, name: String): File {
    val directory = File(context.noBackupFilesDir, name)
    if (!directory.exists()) File(context.cacheDir, name).takeIf { it.isDirectory }?.renameTo(directory)
    return directory
}

/**
 * A downloaded image for storing: if it is larger than [maxPx] (longest side), it is scaled down, otherwise unchanged. Feeds often deliver
 * images with 3000 px and several MB; at most 800 px are displayed. Bytes that are not an image stay as they are.
 */
internal fun shrinkImage(bytes: ByteArray, maxPx: Int): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (maxOf(bounds.outWidth, bounds.outHeight) <= maxPx) return bytes
    val bitmap = decodeScaled(bytes, maxPx) ?: return bytes
    // With transparency as PNG, otherwise it would show up black.
    val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    return ByteArrayOutputStream().also { bitmap.compress(format, JPEG_QUALITY, it) }.toByteArray()
}

/** Writes to a `.part` file first: an abort doesn't leave a half file that would count as present. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val part = File(target.parentFile, "${target.name}$PART_SUFFIX")
    part.writeBytes(bytes)
    if (!part.renameTo(target)) part.delete()
}

internal const val PART_SUFFIX = ".part"

/**
 * File name for [key] (image URL, author name): 128 bits of SHA-256. With `hashCode` (32 bits), from a few thousand
 * addresses on, two images shared a file noticeably often (review D35).
 */
internal fun hashedName(key: String): String =
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(16).joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        "${HEX[value shr 4]}${HEX[value and 0x0f]}"
    }

/** This file; if the image is still under the name [legacy] of an older version, it is moved instead of downloaded again. */
internal fun File.adoptingLegacy(legacy: File): File {
    if (!exists() && legacy.exists()) legacy.renameTo(this)
    return this
}

private const val HEX = "0123456789abcdef"

private const val JPEG_QUALITY = 85
