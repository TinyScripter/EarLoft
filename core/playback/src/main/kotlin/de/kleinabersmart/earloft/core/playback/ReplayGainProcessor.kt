package de.kleinabersmart.earloft.core.playback

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
import de.kleinabersmart.earloft.core.model.ReplayGain
import de.kleinabersmart.earloft.core.model.ReplayGainMode
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Applies ReplayGain (concept 7.3).
 *
 * **Title boundary (spike S1):** the audio buffer runs ahead of audible playback, so
 * the gain is not set in `onMediaItemTransition`. Instead `DefaultAudioSink` (Media3
 * 1.11) flushes the processors with the new title's `periodUid` exactly when its first
 * samples arrive – also with gapless. That's how the [MediaItem] and thus the song is found.
 *
 * **Values:** first those of the chosen source from the database ([onSourceResolved], comes from the
 * loader thread), otherwise the tags of the played file ([ReplayGainAudioSink]); local files only have
 * the latter until tag reading (spike S2).
 */
@OptIn(UnstableApi::class)
internal class ReplayGainProcessor : BaseAudioProcessor() {

    private val databaseValues = ConcurrentHashMap<Long, ReplayGain>()

    /** periodUid → tags; only used on the playback thread. */
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
            else -> AudioFormat.NOT_SET // inactive: playback continues unadjusted instead of failing
        }

    override fun onFlush(streamMetadata: StreamMetadata) {
        // Without a periodUid (e.g. flush unrelated to a title) the previous title stays valid.
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

/** Passes the tags of every new title to the [ReplayGainProcessor] before its samples arrive. */
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

/** Album and position of a title in the queue. */
internal data class QueuePosition(val albumId: Long?, val discNumber: Int, val trackNumber: Int)

private val MediaItem.queuePosition: QueuePosition
    get() = QueuePosition(mediaMetadata.albumIdExtra, mediaMetadata.discNumber ?: 1, mediaMetadata.trackNumber ?: 0)

/** A neighbor from the same album is before or after it in album order. */
internal fun playsAlbumInOrder(previous: QueuePosition?, current: QueuePosition, next: QueuePosition?): Boolean {
    if (current.albumId == null) return false
    val order = compareBy<QueuePosition>({ it.discNumber }, { it.trackNumber })
    fun inOrder(first: QueuePosition?, second: QueuePosition?) =
        first != null && second != null && first.albumId == second.albumId && order.compare(first, second) < 0
    return inOrder(previous, current) || inOrder(current, next)
}
