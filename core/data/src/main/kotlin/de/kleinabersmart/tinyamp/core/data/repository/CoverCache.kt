package de.kleinabersmart.tinyamp.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover-Cache auf dem Gerät (Cache-Verzeichnis, das System darf es räumen): Cover vom Server stehen auch bei
 * ausgeschaltetem Server oder ohne Netz zur Verfügung. Je Album liegt eine Datei in der größten bisher angefragten Größe;
 * die älteste Datei fliegt bei Überschreiten von [MAX_BYTES] heraus. Getrennt von [CoverFiles], den dauerhaften
 * Covern der Uhr.
 */
@Singleton
class CoverCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.cacheDir, "album_covers")

    /** `null`, wenn keine Datei da oder nicht lesbar ist. */
    fun read(albumId: Long): Bitmap? = file(albumId).takeIf { it.isFile }?.let { file ->
        BitmapFactory.decodeFile(file.path)?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    /** Schreibt erst in eine `.part`-Datei, damit ein Abbruch keine halbe Datei hinterlässt. */
    fun write(albumId: Long, bitmap: Bitmap) {
        directory.mkdirs()
        val target = file(albumId)
        val part = File(directory, "${target.name}.part")
        part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!part.renameTo(target)) part.delete()
        trim()
    }

    private fun trim() {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun file(albumId: Long) = File(directory, "$albumId.jpg")

    private companion object {
        const val JPEG_QUALITY = 90
        const val MAX_BYTES = 100L * 1024 * 1024
    }
}
