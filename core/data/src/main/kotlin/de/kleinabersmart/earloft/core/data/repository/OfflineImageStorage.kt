package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Verzeichnis für Bilder, die auch offline da sein sollen: nicht im Cache-Verzeichnis (das darf das System räumen) und nicht
 * im Backup (sie lassen sich neu laden). Lagen sie bisher unter demselben Namen im Cache, ziehen sie einmal mit um.
 */
internal fun offlineImageDirectory(context: Context, name: String): File {
    val directory = File(context.noBackupFilesDir, name)
    if (!directory.exists()) File(context.cacheDir, name).takeIf { it.isDirectory }?.renameTo(directory)
    return directory
}

/**
 * Ein geladenes Bild zum Ablegen: Ist es größer als [maxPx] (längste Seite), verkleinert, sonst unverändert. Feeds liefern oft
 * Bilder mit 3000 px und mehreren MB; angezeigt werden höchstens 800 px. Bytes, die kein Bild sind, bleiben, wie sie sind.
 */
internal fun shrinkImage(bytes: ByteArray, maxPx: Int): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (maxOf(bounds.outWidth, bounds.outHeight) <= maxPx) return bytes
    val bitmap = decodeScaled(bytes, maxPx) ?: return bytes
    // Mit Transparenz als PNG, sonst stünde sie schwarz da.
    val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    return ByteArrayOutputStream().also { bitmap.compress(format, JPEG_QUALITY, it) }.toByteArray()
}

/** Schreibt erst in eine `.part`-Datei: Ein Abbruch hinterlässt keine halbe Datei, die als vorhanden gälte. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val part = File(target.parentFile, "${target.name}$PART_SUFFIX")
    part.writeBytes(bytes)
    if (!part.renameTo(target)) part.delete()
}

internal const val PART_SUFFIX = ".part"

private const val JPEG_QUALITY = 85
