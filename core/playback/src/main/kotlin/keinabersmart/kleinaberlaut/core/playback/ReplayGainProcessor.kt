package keinabersmart.kleinaberlaut.core.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import keinabersmart.kleinaberlaut.core.model.ReplayGain
import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Wendet ReplayGain an (Konzept 7.3).
 *
 * **Titelgrenze (Spike S1):** Der Audio-Puffer läuft der hörbaren Wiedergabe voraus, deshalb wird
 * der Gain nicht in `onMediaItemTransition` gesetzt. Stattdessen flusht `DefaultAudioSink` (Media3
 * 1.11) die Prozessoren genau dann mit der `periodUid` des neuen Titels, wenn dessen erste
 * Samples ankommen – auch bei Gapless. Darüber wird das [MediaItem] und damit der Song gefunden.
 *
 * **Werte:** zuerst die der gewählten Quelle aus der Datenbank ([onSourceResolved], kommt vom
 * Lade-Thread), sonst die Tags der gespielten Datei ([ReplayGainAudioSink]); lokale Dateien haben
 * bis zum Tag-Lesen (Spike S2) nur Letztere.
 */
@OptIn(UnstableApi::class)
internal class ReplayGainProcessor : BaseAudioProcessor() {

    private val databaseValues = ConcurrentHashMap<Long, ReplayGain>()

    /** periodUid → Tags; nur auf dem Playback-Thread benutzt. */
    private val tagValues = object : LinkedHashMap<Any, ReplayGain>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, ReplayGain>?) = size > MAX_REMEMBERED_TAGS
    }

    @Volatile private var mode = ReplayGainMode.AUTO
    @Volatile private var preAmpDb = 0f
    @Volatile var shuffleEnabled = false

    @Volatile private var current: CurrentTrack? = null
    @Volatile private var factor = 1f

    fun setSettings(mode: ReplayGainMode, preAmpDb: Float) {
        this.mode = mode
        this.preAmpDb = preAmpDb
        updateFactor()
    }

    fun onSourceResolved(songId: Long, replayGain: ReplayGain?) {
        if (replayGain != null) databaseValues[songId] = replayGain else databaseValues.remove(songId)
    }

    fun onFormat(periodUid: Any?, metadata: Metadata?) {
        if (periodUid == null) return
        val replayGain = metadata.replayGain()
        if (replayGain != null) tagValues[periodUid] = replayGain else tagValues.remove(periodUid)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioFormat.NOT_SET // inaktiv: Wiedergabe läuft ungeregelt weiter, statt abzubrechen
        }

    override fun onFlush(streamMetadata: StreamMetadata) {
        // Ohne periodUid (z. B. Flush ohne Titelbezug) bleibt der bisherige Titel gültig.
        val periodUid = streamMetadata.periodUid ?: return
        current = describe(streamMetadata.timeline, periodUid)
        updateFactor()
    }

    override fun onReset() {
        current = null
        factor = 1f
        tagValues.clear()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val factor = factor
        val output = replaceOutputBuffer(remaining)
        when {
            factor == 1f -> output.put(inputBuffer)
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT -> while (inputBuffer.hasRemaining()) {
                val sample = (inputBuffer.getShort() * factor).roundToInt()
                output.putShort(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            else -> while (inputBuffer.hasRemaining()) output.putFloat(inputBuffer.getFloat() * factor)
        }
        output.flip()
    }

    private fun describe(timeline: Timeline, periodUid: Any): CurrentTrack? {
        if (timeline.getIndexOfPeriod(periodUid) == C.INDEX_UNSET) return null
        val windowIndex = timeline.getPeriodByUid(periodUid, Timeline.Period()).windowIndex
        val item = timeline.getWindow(windowIndex, Timeline.Window()).mediaItem
        val songId = item.songId
        val replayGain = songId?.let { databaseValues[it] } ?: tagValues[periodUid]
        fun neighbour(index: Int) =
            index.takeIf { it in 0 until timeline.windowCount }?.let { timeline.getWindow(it, Timeline.Window()).mediaItem.queuePosition }
        val inAlbum = !shuffleEnabled &&
            playsAlbumInOrder(neighbour(windowIndex - 1), item.queuePosition, neighbour(windowIndex + 1))
        return CurrentTrack(songId, replayGain, inAlbum)
    }

    private fun updateFactor() {
        val track = current
        val replayGain = track?.replayGain
        val useAlbum = when (mode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> false
            ReplayGainMode.ALBUM -> true
            ReplayGainMode.AUTO -> track?.inAlbum == true
        }
        factor = if (replayGain == null || useAlbum == null) 1f else replayGain.linearFactor(useAlbum, preAmpDb)
        Log.d(TAG, "Song ${track?.songId}: $mode, Album=$useAlbum, $replayGain → Faktor $factor")
    }

    private class CurrentTrack(val songId: Long?, val replayGain: ReplayGain?, val inAlbum: Boolean)

    private companion object {
        const val TAG = "ReplayGain"
        const val MAX_REMEMBERED_TAGS = 16
    }
}

/** Gibt die Tags jedes neuen Titels an den [ReplayGainProcessor] weiter, bevor dessen Samples ankommen. */
@OptIn(UnstableApi::class)
internal class ReplayGainAudioSink(
    sink: AudioSink,
    private val processor: ReplayGainProcessor,
) : ForwardingAudioSink(sink) {

    override fun configure(config: AudioSink.AudioSinkConfig) {
        processor.onFormat(config.mediaPeriodId?.periodUid, config.format.metadata)
        super.configure(config)
    }
}

/** Album und Position eines Titels in der Warteschlange. */
internal data class QueuePosition(val albumId: Long?, val discNumber: Int, val trackNumber: Int)

private val MediaItem.queuePosition: QueuePosition
    get() = QueuePosition(mediaMetadata.albumIdExtra, mediaMetadata.discNumber ?: 1, mediaMetadata.trackNumber ?: 0)

/** Ein Nachbar aus demselben Album steht in Album-Reihenfolge davor bzw. dahinter. */
internal fun playsAlbumInOrder(previous: QueuePosition?, current: QueuePosition, next: QueuePosition?): Boolean {
    if (current.albumId == null) return false
    val order = compareBy<QueuePosition>({ it.discNumber }, { it.trackNumber })
    fun inOrder(first: QueuePosition?, second: QueuePosition?) =
        first != null && second != null && first.albumId == second.albumId && order.compare(first, second) < 0
    return inOrder(previous, current) || inOrder(current, next)
}
