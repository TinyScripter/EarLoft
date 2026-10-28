package keinabersmart.kleinaberlaut.wear.download

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.download.DownloadOutcome
import keinabersmart.kleinaberlaut.core.download.DownloadStorage
import keinabersmart.kleinaberlaut.core.wearsync.FileReceive
import keinabersmart.kleinaberlaut.core.wearsync.receiveFile
import keinabersmart.kleinaberlaut.wear.sync.PhoneConnection
import keinabersmart.kleinaberlaut.wear.sync.WearManifestSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holt eine Datei im Original vom Handy (Wear-Konzept 8.4): für Songs, die nur das Handy hat, und als Ausweg,
 * wenn der Server nicht erreichbar ist. Die Datei entsteht wie beim [keinabersmart.kleinaberlaut.core.download.Downloader]
 * als `.part` und wird erst nach vollständigem Empfang umbenannt.
 */
@Singleton
class PhoneFileFetcher @Inject constructor(
    private val downloadDao: DownloadDao,
    private val storage: DownloadStorage,
    private val channelClient: ChannelClient,
    private val phone: PhoneConnection,
) {
    /** Wie `Downloader.download`: Bei [DownloadOutcome.Retry] bleibt der Eintrag auf `RUNNING`, der Aufrufer entscheidet. */
    suspend fun fetch(
        download: DownloadEntity,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val uid = download.sourceUid
        if (downloadDao.markRunning(uid) == 0) return DownloadOutcome.Cancelled
        val songSource = downloadDao.getSongSource(uid) ?: return DownloadOutcome.Cancelled
        val nodeId = phone.connectedPhoneNodeId() ?: return DownloadOutcome.Retry(IOException("Handy nicht verbunden"))
        // Das Handy kennt die Quelle unter ihrer eigenen `sourceUid`; die Pseudo-Quelle der Uhr trägt sie als `remoteId`.
        val requestUid = if (songSource.sourceId == WearManifestSource.PHONE_SOURCE_ID) songSource.remoteId else uid

        val part = storage.partFileFor(uid)
        storage.ensureDirectory()
        return try {
            var lastReport = 0L
            val received = withContext(Dispatchers.IO) {
                part.outputStream().use { output ->
                    channelClient.receiveFile(nodeId, requestUid, output) { done, total ->
                        val now = System.nanoTime()
                        if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                            lastReport = now
                            if (downloadDao.updateProgress(uid, done, total) == 0) throw RemovedException()
                            onProgress(done, total)
                        }
                    }
                }
            }
            when (received) {
                FileReceive.Missing -> {
                    part.delete()
                    downloadDao.markFailed(uid)
                    DownloadOutcome.Failed(MISSING_ON_PHONE)
                }
                is FileReceive.Received -> finish(uid, songSource.mimeType, received.bytes)
            }
        } catch (e: RemovedException) {
            part.delete()
            DownloadOutcome.Cancelled
        } catch (e: CancellationException) {
            part.delete()
            withContext(NonCancellable) { downloadDao.requeue(uid) }
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Übertragung von $requestUid abgebrochen", e)
            part.delete()
            DownloadOutcome.Retry(e)
        }
    }

    private suspend fun finish(uid: String, mimeType: String?, bytes: Long): DownloadOutcome {
        val part = storage.partFileFor(uid)
        val target = storage.fileFor(uid, mimeType)
        if (!part.renameTo(target)) throw IOException("Umbenennen nach ${target.name} fehlgeschlagen")
        if (downloadDao.markDone(uid, target.absolutePath, target.length()) == 0) {
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Vom Handy geladen: $uid ($bytes Bytes)")
        return DownloadOutcome.Done
    }

    /** Der Eintrag wurde während der Übertragung entfernt (Auswahl am Handy geändert). */
    private class RemovedException : RuntimeException()

    companion object {
        private const val TAG = "PhoneFileFetcher"
        private const val PROGRESS_INTERVAL_NANOS = 500_000_000L

        /** Fehlertext, wenn das Handy keine Datei hat (steht im Statusbericht). */
        const val MISSING_ON_PHONE = "Handy hat keine Kopie"
    }
}
