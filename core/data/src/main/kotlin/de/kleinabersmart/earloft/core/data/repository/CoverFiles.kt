package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Covers as files on the device (Wear concept 8.2): the watch loads them once per album with the downloads
 * and then shows them without a server. The phone preloads them for all server albums (`OfflineImages`) and with the downloads,
 * so covers are available offline too; they stay as long as the album exists.
 */
@Singleton
class CoverFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val directory by lazy { forgetIdFilesOfOtherDatabase(context, File(context.noBackupFilesDir, "covers")) }

    fun has(albumId: Long): Boolean = file(albumId).isFile

    /** `null` if there is no file or it is unreadable. */
    fun read(albumId: Long): Bitmap? = file(albumId).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /** Like [read], but at most [maxPx] in size (thumbnails in the watch's lists). */
    fun readScaled(albumId: Long, maxPx: Int): Bitmap? = decodeScaled(file(albumId), maxPx)

    /** Writes to a `.part` file first, so an abort doesn't leave a half file behind. */
    fun write(albumId: Long, bitmap: Bitmap) {
        directory.mkdirs()
        val target = file(albumId)
        val part = File(directory, "${target.name}.part")
        part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!part.renameTo(target)) part.delete()
    }

    /** Deletes the covers of all albums not in [albumIds]. */
    fun retainOnly(albumIds: Set<Long>): Int {
        val files = directory.listFiles() ?: return 0
        return files.count { it.name != DATABASE_NOTE && it.nameWithoutExtension.toLongOrNull() !in albumIds && it.delete() }
    }

    private fun file(albumId: Long) = File(directory, "$albumId.jpg")

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
