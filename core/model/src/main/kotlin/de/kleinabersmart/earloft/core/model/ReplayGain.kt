package de.kleinabersmart.earloft.core.model

import kotlin.math.min
import kotlin.math.pow

/** Lautstärkeangleichung (Konzept 7.3). */
enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,

    /** Album, wenn die Warteschlange ein Album in Reihenfolge abspielt, sonst Track. */
    AUTO,
}

/** ReplayGain-Werte eines Titels (Gain in dB, Peak linear, 1.0 = Vollaussteuerung). */
data class ReplayGain(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    /**
     * Linearer Verstärkungsfaktor. Fehlt der gewünschte Gain, wird der andere genommen; der
     * Clipping-Schutz nutzt den Peak, der zum verwendeten Gain gehört. Ohne Peak wird nicht über
     * 1.0 verstärkt. Ohne Werte bleibt der Titel unverändert (auch ohne Pre-Amp).
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
        /** Opus `R128_*_GAIN` ist Q7.8 (1/256 dB) bezogen auf −23 LUFS; ReplayGain liegt 5 dB höher. */
        fun r128ToReplayGainDb(q78: Int): Float = q78 / 256f + 5f
    }
}
