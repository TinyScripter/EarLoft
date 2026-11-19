package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover cache on the device (cache directory, the system may clear it): covers from the server are available even when
 * the server is turned off or without network. There is one file per album in the largest size requested so far;
 * the oldest file is evicted when [MAX_BYTES] is exceeded. Separate from [CoverFiles], the permanent
 * covers of the watch.
 */
@Singleton
class CoverCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.cacheDir, "album_covers")

    /** `null` if there is no file or it is unreadable. */
    fun read(albumId: Long): Bitmap? = file(albumId).takeIf { it.isFile }?.let { file ->
        BitmapFactory.decodeFile(file.path)?.also { file.setLastModified(System.currentTimeMillis()) }
    }

    /** Writes to a `.part` file first, so an abort doesn't leave a half file behind. */
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
