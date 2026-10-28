package keinabersmart.kleinaberlaut.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover als Dateien auf dem Gerät (Wear-Konzept 8.2): Die Uhr lädt sie einmal je Album mit den Downloads
 * und zeigt sie danach ohne Server. Am Handy gibt es keine, dort fragt [ArtworkRepository] direkt die Quelle.
 */
@Singleton
class CoverFiles @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.noBackupFilesDir, "covers")

    fun has(albumId: Long): Boolean = file(albumId).isFile

    /** `null`, wenn keine Datei da oder nicht lesbar ist. */
    fun read(albumId: Long): Bitmap? = file(albumId).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** Schreibt erst in eine `.part`-Datei, damit ein Abbruch keine halbe Datei hinterlässt. */
    fun write(albumId: Long, bitmap: Bitmap) {
        directory.mkdirs()
        val target = file(albumId)
        val part = File(directory, "${target.name}.part")
        part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!part.renameTo(target)) part.delete()
    }

    /** Löscht die Cover aller Alben, die nicht in [albumIds] sind. */
    fun retainOnly(albumIds: Set<Long>): Int {
        val files = directory.listFiles() ?: return 0
        return files.count { it.nameWithoutExtension.toLongOrNull() !in albumIds && it.delete() }
    }

    private fun file(albumId: Long) = File(directory, "$albumId.jpg")

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
