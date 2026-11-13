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
 * Kapitel, die in einer Audiodatei auf dem Gerät stecken (Konzept 12.5 „Kapitelquellen“): ID3 `CHAP`, sonst MP4 (`chpl` bzw.
 * Textspur). Für geladene Folgen, deren Feed keine Kapitel nennt; dieselben Leser wie für Dateien aus „Podcasts“.
 */
@Singleton
class AudioFileChapters @Inject constructor(@param:ApplicationContext context: Context) {
    private val tagReader = LocalTagReader(context)

    /** Leer, wenn die Datei keine Kapitel hat oder nicht lesbar ist. [durationMs] begrenzt das letzte MP4-Kapitel. */
    suspend fun read(file: File, mimeType: String?, durationMs: Long): List<LocalBookChapter> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        tagReader.readSpoken(Uri.fromFile(file))?.chapters?.takeIf { it.isNotEmpty() }
            ?: if (isMp4(mimeType, file.name)) marks(file).toChapters(durationMs) else emptyList()
    }

    private fun marks(file: File): List<Mp4ChapterMark> = try {
        RandomAccessFile(file, "r").use { Mp4Chapters.read(ChannelSource(it.channel)) }
    } catch (e: IOException) {
        Log.w(TAG, "Kapitel von ${file.name} nicht lesbar", e)
        emptyList()
    }

    private companion object {
        const val TAG = "AudioFileChapters"
    }
}
