package de.kleinabersmart.earloft.core.download

import android.net.Network
import android.util.Log
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RemoteTranscoding
import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.isAuthRejection
import de.kleinabersmart.earloft.core.network.subsonic.subsonicErrorIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/** Ergebnis eines einzelnen Downloads. */
sealed interface DownloadOutcome {
    data object Done : DownloadOutcome

    /**
     * Dauerhaft fehlgeschlagen (z. B. Datei auf dem Server weg, Speicher voll). [sourceUnavailable]: Dieses Gerät kennt die
     * Quelle gar nicht, das trifft alle Titel von ihr.
     */
    data class Failed(val reason: String, val sourceUnavailable: Boolean = false) : DownloadOutcome

    /** Vorübergehender Fehler (Netz, abgelehnte Anmeldung); der Eintrag steht noch auf `RUNNING`. */
    data class Retry(val cause: Exception) : DownloadOutcome {
        /** Der Server hat die Zugangsdaten abgelehnt: Mit neuen klappt es (die Uhr fragt dann beim Handy nach). */
        val isAuthRejection: Boolean get() = cause.isAuthRejection
    }

    /** Der Eintrag wurde während des Ladens entfernt. */
    data object Cancelled : DownloadOutcome
}

/**
 * Lädt eine Song-Quelle vollständig in den App-Speicher (Konzept 5.3). Die Datei entsteht als
 * `.part` und wird erst nach vollständigem Empfang umbenannt; abgebrochene Downloads hinterlassen
 * so nie eine halbe Datei, die als fertig gilt.
 *
 * Auth übernimmt der gemeinsame OkHttpClient anhand der Stream-URL. `no-store` hält die Datei
 * aus dem HTTP-Cache heraus.
 */
class Downloader @Inject constructor(
    private val downloadDao: DownloadDao,
    private val sources: MusicDataSources,
    private val client: OkHttpClient,
    private val storage: DownloadStorage,
) {
    /**
     * Lädt [download] herunter. Bei [DownloadOutcome.Retry] bleibt der Eintrag auf `RUNNING` – der
     * Aufrufer entscheidet, ob er ihn neu einreiht oder als fehlgeschlagen markiert.
     *
     * Mit [network] läuft die Verbindung über genau dieses Netz (die Uhr lädt über WLAN, nie über den
     * Bluetooth-Proxy des Handys).
     */
    suspend fun download(
        download: DownloadEntity,
        profile: DownloadProfile = DownloadProfile.ORIGINAL,
        network: Network? = null,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val uid = download.sourceUid
        if (downloadDao.markRunning(uid) == 0) return DownloadOutcome.Cancelled
        val part = storage.partFileFor(uid)
        return try {
            fetch(uid, profile, network, onProgress).also { outcome ->
                if (outcome is DownloadOutcome.Failed) downloadDao.markFailed(uid)
                if (outcome !is DownloadOutcome.Done) part.delete()
            }
        } catch (e: CancellationException) {
            part.delete()
            withContext(NonCancellable) { downloadDao.requeue(uid) }
            throw e
        } catch (e: IOException) {
            part.delete()
            DownloadOutcome.Retry(e)
        }
    }

    private suspend fun fetch(
        uid: String,
        profile: DownloadProfile,
        network: Network?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadOutcome {
        val songSource = downloadDao.getSongSource(uid) ?: return DownloadOutcome.Cancelled
        val source = sources[songSource.sourceId]
            ?: return DownloadOutcome.Failed("Quelle nicht verfügbar", sourceUnavailable = true)
        val location = profile.transcodeTo
            ?.let { (codec, kbps) -> (source as? RemoteTranscoding)?.transcodedPlayback(songSource.remoteId, codec, kbps) }
            ?: source.resolvePlayback(songSource.remoteId) as? PlaybackLocation.Remote
            ?: return DownloadOutcome.Failed("Keine Server-Quelle")

        val url = location.uri.toString().toHttpUrlOrNull()
            ?: return DownloadOutcome.Failed("Ungültige Adresse")
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder().noStore().build())
            .build()
        val call = clientFor(network, url.host).newCall(request)
        val cancelCall = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty()
                    when {
                        response.code in 500..599 -> throw IOException("HTTP ${response.code}")
                        // Abgelehnte Zugangsdaten sind vorübergehend: Passwort bzw. Token können sich geändert haben.
                        response.code == HTTP_UNAUTHORIZED -> throw HttpStatusException(response.code)
                        !response.isSuccessful -> return@withContext DownloadOutcome.Failed("HTTP ${response.code}")
                        // Subsonic meldet Fehler (z. B. Auth) als JSON/XML mit HTTP 200, andere Server per Status.
                        contentType.startsWith("application/json") || contentType.contains("xml") -> {
                            val error = subsonicErrorIn(response.peekBody(MAX_ERROR_BODY_BYTES).string())
                            if (error?.isRejectedCredentials == true) throw error
                            return@withContext DownloadOutcome.Failed(error?.message ?: "Server-Fehler statt Audiodatei")
                        }
                    }
                    val total = response.body.contentLength().takeIf { it >= 0 }
                    if (total != null && storage.freeBytes() < total + FREE_SPACE_RESERVE_BYTES) {
                        return@withContext DownloadOutcome.Failed("Zu wenig Speicher")
                    }
                    writeToPart(uid, response.body.byteStream(), total, onProgress)
                        ?: return@withContext DownloadOutcome.Cancelled
                    // Die Endung folgt der Antwort: Eine transkodierte Datei hat einen anderen Typ als die Quelle.
                    val responseType = contentType.substringBefore(';').trim().lowercase()
                    val mimeType = responseType.takeIf { it.startsWith("audio/") }
                        ?: songSource.mimeType
                        ?: responseType.ifEmpty { null }
                    finish(uid, mimeType, total)
                }
            }
        } finally {
            cancelCall.dispose()
        }
    }

    /** Loopback (z. B. `adb reverse` im Emulator) verlässt das Gerät nie und lässt sich an kein Netz binden. */
    private fun clientFor(network: Network?, host: String): OkHttpClient =
        if (network == null || host == "localhost" || host == "127.0.0.1" || host == "[::1]") {
            client
        } else {
            client.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { host -> network.getAllByName(host).toList() }
                .build()
        }

    /** @return geschriebene Bytes oder `null`, wenn der Download zwischendurch entfernt wurde. */
    private suspend fun writeToPart(
        uid: String,
        input: java.io.InputStream,
        total: Long?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long? {
        val part = storage.partFileFor(uid)
        storage.ensureDirectory()
        var written = 0L
        var lastReport = 0L
        part.outputStream().use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                val now = System.nanoTime()
                if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                    lastReport = now
                    if (downloadDao.updateProgress(uid, written, total) == 0) return null
                    onProgress(written, total)
                }
            }
        }
        if (total != null && written != total) throw IOException("Unvollständig: $written von $total Bytes")
        return written
    }

    private suspend fun finish(uid: String, mimeType: String?, total: Long?): DownloadOutcome {
        val part = storage.partFileFor(uid)
        val target = storage.fileFor(uid, mimeType)
        if (!part.renameTo(target)) throw IOException("Umbenennen nach ${target.name} fehlgeschlagen")
        val size = target.length()
        if (downloadDao.markDone(uid, target.absolutePath, size) == 0) {
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Download fertig: $uid ($size Bytes${if (total == null) ", Größe vorher unbekannt" else ""})")
        return DownloadOutcome.Done
    }

    private companion object {
        const val TAG = "Downloader"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_NANOS = 500_000_000L
        const val FREE_SPACE_RESERVE_BYTES = 100L * 1024 * 1024
        const val HTTP_UNAUTHORIZED = 401

        /** Fehlerantworten sind klein; mehr wird nie gelesen. */
        const val MAX_ERROR_BODY_BYTES = 16L * 1024
    }
}
