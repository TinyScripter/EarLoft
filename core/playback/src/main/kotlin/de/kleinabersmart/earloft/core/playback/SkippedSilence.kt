package de.kleinabersmart.earloft.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.util.concurrent.atomic.AtomicLong

/**
 * Content time skipped by "skip silence" (statistics "saved"). Media3 continuously factors skipped frames into
 * the position (`DefaultAudioSink.applySkipping`); `DISCONTINUITY_REASON_SILENCE_SKIP` only comes about 100 ms later,
 * and the jump between old and new position is then only the progress of one pass. That's why
 * [CountingAudioProcessorChain] counts along at the processor's counter, exactly when the sink reads it for the position.
 *
 * [onFrameCount] and [sampleRate] come from the player thread, [drainMs] from the main thread.
 */
internal class SkippedSilence {
    /** Sample rate of the input; the counter counts frames of it. */
    @Volatile
    var sampleRate = 0

    private var lastFrames = 0L
    private val pendingUs = AtomicLong()

    /** Counter value of the processor; it drops to 0 on flush (seeking, file change) and counts on from there. */
    fun onFrameCount(frames: Long) {
        val skipped = if (frames >= lastFrames) frames - lastFrames else frames
        lastFrames = frames
        val rate = sampleRate
        if (skipped > 0 && rate > 0) pendingUs.addAndGet(skipped * MICROS_PER_SECOND / rate)
    }

    /** Skipped content time since the last call in ms; the remainder below 1 ms is kept. */
    fun drainMs(): Long {
        val us = pendingUs.getAndSet(0)
        pendingUs.addAndGet(us % MICROS_PER_MS)
        return us / MICROS_PER_MS
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
        const val MICROS_PER_MS = 1_000L
    }
}

/** The sink's chain, which reports every read value of skipped frames to [silence]. */
@OptIn(UnstableApi::class)
internal class CountingAudioProcessorChain(
    private val chain: AudioProcessorChain,
    private val silence: SkippedSilence,
) : AudioProcessorChain by chain {
    override fun getSkippedOutputFrameCount(): Long = chain.skippedOutputFrameCount.also(silence::onFrameCount)
}

/** Remembers the sample rate of every newly configured format for [SkippedSilence]. */
@OptIn(UnstableApi::class)
internal class SilenceRateAudioSink(sink: AudioSink, private val silence: SkippedSilence) : ForwardingAudioSink(sink) {
    override fun configure(config: AudioSink.AudioSinkConfig) {
        silence.sampleRate = config.format.sampleRate.coerceAtLeast(0)
        super.configure(config)
    }
}
