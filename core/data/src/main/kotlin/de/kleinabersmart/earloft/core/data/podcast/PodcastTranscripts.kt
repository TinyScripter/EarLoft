package de.kleinabersmart.earloft.core.data.podcast

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.model.Transcript
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import de.kleinabersmart.earloft.core.network.podcast.TranscriptFormat
import de.kleinabersmart.earloft.core.network.podcast.parseTranscript
import de.kleinabersmart.earloft.core.network.podcast.transcriptFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Result of [PodcastTranscripts.load]. */
sealed interface TranscriptResult {
    /** The episode has no (readable) transcript. */
    data object None : TranscriptResult

    /** There is one, but it could not be loaded (offline, server error, unreadable). */
    data object Unavailable : TranscriptResult

    data class Ready(val transcript: Transcript) : TranscriptResult
}

/**
 * Episode transcripts (`podcast:transcript`, concept 12.5 "Transcripts"). They are loaded on first display and right
 * after an episode is downloaded; the text then lives in the device cache (one file per URL), so it is available offline
 * too. Nothing is generated (no speech recognition).
 */
@Singleton
class PodcastTranscripts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val api: PodcastFeedApi,
) {
    suspend fun load(bookId: Long): TranscriptResult {
        val book = bookDao.book(bookId) ?: return TranscriptResult.None
        val url = book.transcriptUrl ?: return TranscriptResult.None
        val declared = transcriptFormat(book.transcriptType, url)
        cached(url)?.let { text ->
            val result = parse(text, declared)
            if (result is TranscriptResult.Ready) return result
            // The cache only holds readable content; whatever can't be read (anymore) is dropped and loaded again.
            drop(url)
        }
        val text = try {
            api.transcript(url).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Transcript of ${book.title} not available", e)
            return TranscriptResult.Unavailable
        }
        // Parse first, then store: otherwise a broken transcript would stay in the cache, and "Try again" would read it again.
        return parse(text, declared).also { if (it is TranscriptResult.Ready) store(url, text) }
    }

    /** After an episode is downloaded: fetch the transcript too, so it is available offline. Errors are non-critical. */
    suspend fun prefetch(bookId: Long) {
        val book = bookDao.book(bookId) ?: return
        val url = book.transcriptUrl ?: return
        if (cached(url) != null) return
        try {
            val text = api.transcript(url).text
            if (parse(text, transcriptFormat(book.transcriptType, url)) is TranscriptResult.Ready) store(url, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.i(TAG, "Transcript not prefetched: ${e.message}")
        }
    }

    private suspend fun parse(text: String, declared: TranscriptFormat?): TranscriptResult = withContext(Dispatchers.Default) {
        try {
            val transcript = parseTranscript(text, declared)
            if (transcript.segments.isEmpty()) TranscriptResult.None else TranscriptResult.Ready(transcript)
        } catch (e: IOException) {
            Log.w(TAG, "Transcript unreadable", e)
            TranscriptResult.Unavailable
        }
    }

    private suspend fun cached(url: String): String? = withContext(Dispatchers.IO) {
        fileFor(url).takeIf { it.isFile }?.readText()
    }

    private suspend fun store(url: String, text: String) = withContext(Dispatchers.IO) {
        val file = fileFor(url)
        file.parentFile?.mkdirs()
        // Write completely first, then rename: an aborted write doesn't leave a half transcript behind.
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) temp.delete()
    }

    private suspend fun drop(url: String) = withContext(Dispatchers.IO) { fileFor(url).delete() }

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, DIR), hash.take(32))
    }

    private companion object {
        const val TAG = "PodcastTranscripts"
        const val DIR = "transcripts"
    }
}
