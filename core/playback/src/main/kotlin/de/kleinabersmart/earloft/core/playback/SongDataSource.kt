package de.kleinabersmart.earloft.core.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver
import de.kleinabersmart.earloft.core.data.repository.ResolvedPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.ReplayGain
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Öffnet `earloft://song/<id>` an der besten erreichbaren Quelle (Konzept 6.5/7.2). Scheitert das
 * Öffnen am Dateianfang, wird die nächste Quelle versucht. Mitten in der Datei (Seek, erneuter
 * Versuch nach Abbruch) bleibt es bei der Quelle, die den Anfang geliefert hat – Byte-Positionen
 * verschiedener Dateien passen nicht zueinander.
 *
 * Läuft auf dem Lade-Thread von ExoPlayer, daher ist Blockieren hier erlaubt.
 */
@OptIn(UnstableApi::class)
internal class SongDataSource(
    private val upstream: DataSource,
    /** Siehe [PlaybackSourceResolver.candidates]. */
    private val candidatesOf: suspend (songId: Long) -> List<ResolvedPlayback>,
    private val health: SourceHealth,
    /** Text der Fehlermeldung, wenn keine Quelle erreichbar ist; Android Auto zeigt ihn dem Fahrer. */
    private val unavailableMessage: String = "No playable source",
    /** ReplayGain der gewählten Quelle; wird vor dem Abspielen der Samples gemeldet. */
    private val onResolved: (songId: Long, replayGain: ReplayGain?) -> Unit,
    /** Der Ort, der den Anfang geliefert hat (Titel-Info). */
    private val onOpened: (songId: Long, playback: ResolvedPlayback) -> Unit = { _, _ -> },
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val songId = AppUris.parseSongId(dataSpec.uri.toString()) ?: return upstream.open(dataSpec)
        val candidates = health.reachableFirst(runBlocking { candidatesOf(songId) })
        if (candidates.isEmpty()) throw IOException(unavailableMessage)

        val tries = if (dataSpec.position == 0L) {
            candidates
        } else {
            val openedFrom = health.openedFrom(songId)
            listOf(candidates.firstOrNull { it.key == openedFrom } ?: candidates.first())
        }
        var lastError: IOException? = null
        for (candidate in tries) {
            try {
                val length = upstream.open(candidate.applyTo(dataSpec))
                health.succeeded(candidate.sourceId)
                health.rememberOpened(songId, candidate.key)
                onResolved(songId, candidate.replayGain)
                if (dataSpec.position == 0L) onOpened(songId, candidate)
                return length
            } catch (e: IOException) {
                Log.w(TAG, "Song $songId: ${candidate.key} nicht erreichbar", e)
                runCatching { upstream.close() }
                health.failed(candidate.sourceId)
                lastError = e
            }
        }
        throw lastError ?: IOException(unavailableMessage)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(
        private val upstream: DataSource.Factory,
        private val sourceResolver: PlaybackSourceResolver,
        private val health: SourceHealth,
        private val unavailableMessage: String,
        private val onResolved: (songId: Long, replayGain: ReplayGain?) -> Unit,
        private val onOpened: (songId: Long, playback: ResolvedPlayback) -> Unit = { _, _ -> },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SongDataSource(upstream.createDataSource(), sourceResolver::candidates, health, unavailableMessage, onResolved, onOpened)
    }

    private companion object {
        const val TAG = "SongDataSource"
    }
}

/** Kennung eines Orts – für Streams der Cache-Schlüssel (`sourceUid`), sonst die URI. */
private val ResolvedPlayback.key: String
    get() = when (val location = location) {
        is PlaybackLocation.LocalFile -> location.uri.toString()
        is PlaybackLocation.Remote -> location.cacheKey
    }

@OptIn(UnstableApi::class)
private fun ResolvedPlayback.applyTo(dataSpec: DataSpec): DataSpec = when (val location = location) {
    is PlaybackLocation.LocalFile -> dataSpec.withUri(location.uri)
    is PlaybackLocation.Remote -> dataSpec.buildUpon().setUri(location.uri).setKey(location.cacheKey).build()
}

/**
 * Merkt sich, welche Quellen gerade nicht erreichbar waren, damit nicht jeder folgende Titel erst
 * in denselben Timeout läuft, und von welchem Ort ein Song zuletzt geöffnet wurde.
 */
internal class SourceHealth(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val openedFrom = ConcurrentHashMap<Long, String>()

    fun failed(sourceId: String?) {
        if (sourceId != null) failedAt[sourceId] = clock()
    }

    fun succeeded(sourceId: String?) {
        if (sourceId != null) failedAt.remove(sourceId)
    }

    /** Stabil: Reihenfolge bleibt, nur kürzlich gescheiterte Quellen rücken nach hinten. */
    fun reachableFirst(candidates: List<ResolvedPlayback>): List<ResolvedPlayback> {
        val now = clock()
        return candidates.sortedBy { candidate ->
            candidate.sourceId?.let { failedAt[it] }?.let { now - it < RETRY_AFTER_MS } == true
        }
    }

    fun rememberOpened(songId: Long, key: String) {
        openedFrom[songId] = key
    }

    fun openedFrom(songId: Long): String? = openedFrom[songId]

    private companion object {
        const val RETRY_AFTER_MS = 2 * 60 * 1000L
    }
}
