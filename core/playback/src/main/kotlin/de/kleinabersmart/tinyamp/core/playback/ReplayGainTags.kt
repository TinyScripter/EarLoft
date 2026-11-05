package de.kleinabersmart.tinyamp.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import de.kleinabersmart.tinyamp.core.model.ReplayGain

/**
 * Liest ReplayGain aus den Tags, die Media3 beim Abspielen ohnehin parst: Vorbis-Kommentare
 * (FLAC, Ogg, Opus inkl. `R128_*`), ID3-`TXXX` (MP3) und iTunes-`----`-Atome (MP4).
 */
@OptIn(UnstableApi::class)
internal fun Metadata?.replayGain(): ReplayGain? {
    if (this == null) return null
    val tags = mutableMapOf<String, String>()
    for (i in 0 until length()) {
        when (val entry = get(i)) {
            is VorbisComment -> tags.putIfAbsent(entry.key.uppercase(), entry.value)
            is TextInformationFrame -> if (entry.id == "TXXX") {
                val description = entry.description ?: continue
                entry.values.firstOrNull()?.let { tags.putIfAbsent(description.uppercase(), it) }
            }
            is InternalFrame -> tags.putIfAbsent(entry.description.uppercase(), entry.text)
        }
    }
    fun number(key: String) = tags[key]?.let(::parseLeadingNumber)
    fun r128(key: String) = tags[key]?.trim()?.toIntOrNull()?.let(ReplayGain::r128ToReplayGainDb)

    return ReplayGain(
        trackGainDb = number("REPLAYGAIN_TRACK_GAIN") ?: r128("R128_TRACK_GAIN"),
        trackPeak = number("REPLAYGAIN_TRACK_PEAK"),
        albumGainDb = number("REPLAYGAIN_ALBUM_GAIN") ?: r128("R128_ALBUM_GAIN"),
        albumPeak = number("REPLAYGAIN_ALBUM_PEAK"),
    ).takeUnless { it.isEmpty }
}

private val LEADING_NUMBER = Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)""")

/** `"-6.50 dB"` → -6.5; manche Tagger schreiben ein Dezimalkomma. */
private fun parseLeadingNumber(value: String): Float? =
    LEADING_NUMBER.find(value)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()
