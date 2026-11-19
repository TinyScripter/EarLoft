package de.kleinabersmart.earloft.core.model

import kotlin.math.min
import kotlin.math.pow

/** Volume normalization (concept 7.3). */
enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,

    /** Album when the queue plays an album in order, otherwise track. */
    AUTO,
}

/** ReplayGain values of a track (gain in dB, peak linear, 1.0 = full scale). */
data class ReplayGain(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    /**
     * Linear gain factor. If the desired gain is missing, the other one is used; the
     * clipping protection uses the peak that belongs to the gain used. Without a peak there is no amplification above
     * 1.0. Without values the track stays unchanged (also without pre-amp).
     */
    fun linearFactor(useAlbum: Boolean, preAmpDb: Float = 0f): Float {
        val album = albumGainDb?.let { it to albumPeak }
        val track = trackGainDb?.let { it to trackPeak }
        val (gainDb, peak) = (if (useAlbum) album ?: track else track ?: album) ?: return 1f
        val raw = 10.0.pow((gainDb + preAmpDb) / 20.0).toFloat()
        val limit = 1f / (peak?.takeIf { it > 0f } ?: 1f)
        return min(raw, limit)
    }

    companion object {
        /** Opus `R128_*_GAIN` is Q7.8 (1/256 dB) relative to −23 LUFS; ReplayGain is 5 dB higher. */
        fun r128ToReplayGainDb(q78: Int): Float = q78 / 256f + 5f
    }
}
