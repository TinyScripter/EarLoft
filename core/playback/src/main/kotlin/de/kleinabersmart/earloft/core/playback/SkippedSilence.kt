package de.kleinabersmart.earloft.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.util.concurrent.atomic.AtomicLong

/**
 * Inhaltszeit, die „Stille kürzen“ übersprungen hat (Statistik „gespart“). Media3 rechnet übersprungene Frames laufend in
 * die Position ein (`DefaultAudioSink.applySkipping`); `DISCONTINUITY_REASON_SILENCE_SKIP` kommt erst rund 100 ms danach,
 * und der Sprung zwischen alter und neuer Position ist dann nur noch der Fortschritt eines Durchlaufs. Deshalb zählt
 * [CountingAudioProcessorChain] am Zähler des Prozessors mit, genau dann, wenn der Sink ihn für die Position liest.
 *
 * [onFrameCount] und [sampleRate] kommen vom Player-Thread, [drainMs] vom Main-Thread.
 */
internal class SkippedSilence {
    /** Abtastrate des Eingangs; der Zähler zählt Frames davon. */
    @Volatile
    var sampleRate = 0

    private var lastFrames = 0L
    private val pendingUs = AtomicLong()

    /** Zählerstand des Prozessors; er fällt beim Flushen (Spulen, Wechsel der Datei) auf 0 und zählt von dort weiter. */
    fun onFrameCount(frames: Long) {
        val skipped = if (frames >= lastFrames) frames - lastFrames else frames
        lastFrames = frames
        val rate = sampleRate
        if (skipped > 0 && rate > 0) pendingUs.addAndGet(skipped * MICROS_PER_SECOND / rate)
    }

    /** Übersprungene Inhaltszeit seit dem letzten Aufruf in ms; der Rest unter 1 ms bleibt stehen. */
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

/** Die Kette des Sinks, die jeden gelesenen Stand der übersprungenen Frames an [silence] meldet. */
@OptIn(UnstableApi::class)
internal class CountingAudioProcessorChain(
    private val chain: AudioProcessorChain,
    private val silence: SkippedSilence,
) : AudioProcessorChain by chain {
    override fun getSkippedOutputFrameCount(): Long = chain.skippedOutputFrameCount.also(silence::onFrameCount)
}

/** Merkt sich die Abtastrate jedes neu eingerichteten Formats für [SkippedSilence]. */
@OptIn(UnstableApi::class)
internal class SilenceRateAudioSink(sink: AudioSink, private val silence: SkippedSilence) : ForwardingAudioSink(sink) {
    override fun configure(config: AudioSink.AudioSinkConfig) {
        silence.sampleRate = config.format.sampleRate.coerceAtLeast(0)
        super.configure(config)
    }
}
