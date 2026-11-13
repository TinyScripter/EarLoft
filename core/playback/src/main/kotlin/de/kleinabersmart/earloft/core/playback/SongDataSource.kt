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
    /** Ob ein Stream mit diesem Cache-Schlüssel ganz im Stream-Cache liegt, siehe [preferCachedOriginals]. */
    private val isFullyCached: (cacheKey: String) -> Boolean = { false },
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val songId = AppUris.parseSongId(dataSpec.uri.toString()) ?: return upstream.open(dataSpec)
        val candidates = health.reachableFirst(runBlocking { candidatesOf(songId) }.preferCachedOriginals(isFullyCached))
        if (candidates.isEmpty()) throw IOException(unavailableMessage)

        val tries = if (dataSpec.position == 0L) {
            candidates
        } else {
            // Auch wenn es den Ort nicht mehr unter den Kandidaten gibt (z. B. die Umwandlung nach dem Wechsel ins WLAN):
            // Byte-Positionen einer anderen Fassung passen nicht.
            val openedFrom = health.openedFrom(songId)
            listOf(candidates.firstOrNull { it.key == openedFrom?.key } ?: openedFrom ?: candidates.first())
        }
        var lastError: IOException? = null
        for (candidate in tries) {
            try {
                val length = upstream.open(candidate.applyTo(dataSpec))
                health.succeeded(candidate.sourceId)
                health.rememberOpened(songId, candidate)
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
        private val isFullyCached: (cacheKey: String) -> Boolean = { false },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SongDataSource(upstream.createDataSource(), sourceResolver::candidates, health, unavailableMessage, onResolved, onOpened, isFullyCached)
    }

    private companion object {
        const val TAG = "SongDataSource"
    }
}

/**
 * Über mobile Daten (umgewandelte Kandidaten vorhanden): Ein Original, das schon ganz im Stream-Cache liegt, kostet nichts und
 * klingt besser – es rückt vor die Umwandlungen. Lokale Dateien und Downloads davor bleiben, wo sie sind.
 */
internal fun List<ResolvedPlayback>.preferCachedOriginals(isFullyCached: (cacheKey: String) -> Boolean): List<ResolvedPlayback> {
    val firstTranscoded = indexOfFirst { it.transcodedTo != null }
    if (firstTranscoded < 0) return this
    val rest = drop(firstTranscoded)
    val cached = rest.filter { it.transcodedTo == null && (it.location as? PlaybackLocation.Remote)?.cacheKey?.let(isFullyCached) == true }
    if (cached.isEmpty()) return this
    return take(firstTranscoded) + cached + rest.filterNot { it in cached }
}

/** Kennung eines Orts – für Streams der Cache-Schlüssel (`sourceUid`), sonst die URI. */
internal val ResolvedPlayback.key: String
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
    private val openedFrom = ConcurrentHashMap<Long, ResolvedPlayback>()

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

    fun rememberOpened(songId: Long, playback: ResolvedPlayback) {
        openedFrom[songId] = playback
    }

    fun openedFrom(songId: Long): ResolvedPlayback? = openedFrom[songId]

    private companion object {
        const val RETRY_AFTER_MS = 2 * 60 * 1000L
    }
}
