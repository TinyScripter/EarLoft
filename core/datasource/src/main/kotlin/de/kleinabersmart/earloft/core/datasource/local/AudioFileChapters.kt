package de.kleinabersmart.earloft.core.datasource.local

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chapters embedded in an audio file on the device (concept 12.5 "chapter sources"): ID3 `CHAP`, otherwise MP4 (`chpl` or
 * text track). For downloaded episodes whose feed names no chapters; the same readers as for files from "Podcasts".
 */
@Singleton
class AudioFileChapters @Inject constructor(@param:ApplicationContext context: Context) {
    private val tagReader = LocalTagReader(context)

    /** Empty if the file has no chapters or isn't readable. [durationMs] bounds the last MP4 chapter. */
    suspend fun read(file: File, mimeType: String?, durationMs: Long): List<LocalBookChapter> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        tagReader.readSpoken(Uri.fromFile(file))?.chapters?.takeIf { it.isNotEmpty() }
            ?: if (isMp4(mimeType, file.name)) marks(file).toChapters(durationMs) else emptyList()
    }

    private fun marks(file: File): List<Mp4ChapterMark> = try {
        RandomAccessFile(file, "r").use { Mp4Chapters.read(ChannelSource(it.channel)) }
    } catch (e: IOException) {
        Log.w(TAG, "Chapters of ${file.name} unreadable", e)
        emptyList()
    }

    private companion object {
        const val TAG = "AudioFileChapters"
    }
}
