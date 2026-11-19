package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Kind of images from the internet; each has its own directory. */
enum class OnlineImageKind(internal val directory: String) {
    ARTIST("artist-images"),
    ALBUM("online-covers"),
}

/**
 * Images from the internet as files: a hit is downloaded once and then shown offline.
 * A search without a hit records a `.none` file and is only repeated after [RETRY_AFTER_MS],
 * so it isn't requested again on every scroll.
 */
@Singleton
class OnlineImageFiles @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val root = File(context.noBackupFilesDir, "online")

    fun read(kind: OnlineImageKind, id: Long): Bitmap? =
        file(kind, id, "jpg").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    fun write(kind: OnlineImageKind, id: Long, bytes: ByteArray) {
        val target = file(kind, id, "jpg")
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) part.delete()
        file(kind, id, "none").delete()
    }

    fun markNotFound(kind: OnlineImageKind, id: Long) {
        val marker = file(kind, id, "none")
        marker.parentFile?.mkdirs()
        marker.writeText("")
    }

    fun isRecentlyNotFound(kind: OnlineImageKind, id: Long, now: Long = System.currentTimeMillis()): Boolean {
        val marker = file(kind, id, "none")
        return marker.isFile && now - marker.lastModified() < RETRY_AFTER_MS
    }

    /** Removes all images and negative entries of a kind, e.g. after switching the option off. */
    fun clear(kind: OnlineImageKind) {
        File(root, kind.directory).deleteRecursively()
    }

    private fun file(kind: OnlineImageKind, id: Long, extension: String) = File(File(root, kind.directory), "$id.$extension")

    private companion object {
        const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    }
}
