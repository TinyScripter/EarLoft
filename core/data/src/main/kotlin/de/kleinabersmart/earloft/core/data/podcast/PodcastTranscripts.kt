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

/** Ergebnis von [PodcastTranscripts.load]. */
sealed interface TranscriptResult {
    /** Die Folge hat kein (lesbares) Transkript. */
    data object None : TranscriptResult

    /** Es gibt eins, aber es ließ sich nicht laden (offline, Serverfehler, unlesbar). */
    data object Unavailable : TranscriptResult

    data class Ready(val transcript: Transcript) : TranscriptResult
}

/**
 * Transkripte der Folgen (`podcast:transcript`, Konzept 12.5 „Transkripte“). Geladen wird beim ersten Anzeigen und gleich
 * nach dem Download einer Folge; der Text liegt danach im Cache des Geräts (je Adresse eine Datei), damit er auch offline da
 * ist. Erzeugt wird nichts (keine Spracherkennung).
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
        val text = cached(url) ?: try {
            val fetched = api.transcript(url)
            store(url, fetched.text)
            fetched.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Transkript von ${book.title} nicht abrufbar", e)
            return TranscriptResult.Unavailable
        }
        return parse(text, declared)
    }

    /** Nach dem Download einer Folge: Transkript gleich mitnehmen, damit es offline da ist. Fehler sind unkritisch. */
    suspend fun prefetch(bookId: Long) {
        val url = bookDao.book(bookId)?.transcriptUrl ?: return
        if (cached(url) != null) return
        try {
            store(url, api.transcript(url).text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.i(TAG, "Transkript nicht vorab geladen: ${e.message}")
        }
    }

    private suspend fun parse(text: String, declared: TranscriptFormat?): TranscriptResult = withContext(Dispatchers.Default) {
        try {
            val transcript = parseTranscript(text, declared)
            if (transcript.segments.isEmpty()) TranscriptResult.None else TranscriptResult.Ready(transcript)
        } catch (e: IOException) {
            Log.w(TAG, "Transkript unlesbar", e)
            TranscriptResult.Unavailable
        }
    }

    private suspend fun cached(url: String): String? = withContext(Dispatchers.IO) {
        fileFor(url).takeIf { it.isFile }?.readText()
    }

    private suspend fun store(url: String, text: String) = withContext(Dispatchers.IO) {
        val file = fileFor(url)
        file.parentFile?.mkdirs()
        // Erst ganz schreiben, dann umbenennen: Ein abgebrochener Schreibvorgang hinterlässt kein halbes Transkript.
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) temp.delete()
    }

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, DIR), hash.take(32))
    }

    private companion object {
        const val TAG = "PodcastTranscripts"
        const val DIR = "transcripts"
    }
}
