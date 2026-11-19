package de.kleinabersmart.earloft.core.datasource.local

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.charset.Charset

/**
 * Tags of a local file that MediaStore doesn't provide (concept 6.2). Empty values are `null`.
 * MBIDs: [recordingMbid] ↔ `mbidTrack`, [releaseMbid] ↔ `mbidAlbum` – as with Subsonic.
 */
internal data class EmbeddedTags(
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val artistMbid: String? = null,
    val albumArtistMbid: String? = null,
    val lyrics: String? = null,
) {
    companion object {
        val EMPTY = EmbeddedTags()

        private const val UFID_OWNER = "http://musicbrainz.org"
        private val MBID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

        /** Reads the entries of all tracks; the first value found wins in each case. */
        @OptIn(UnstableApi::class)
        fun from(metadata: List<Metadata>): EmbeddedTags {
            val tags = mutableMapOf<String, String>()
            var ufidRecording: String? = null
            var uslt: String? = null
            for (block in metadata) {
                for (i in 0 until block.length()) {
                    when (val entry = block[i]) {
                        is VorbisComment -> tags.putIfAbsent(entry.key.uppercase(), entry.value)
                        is TextInformationFrame -> when (entry.id) {
                            "TXXX" -> {
                                val description = entry.description ?: continue
                                entry.values.firstOrNull()?.let { tags.putIfAbsent(description.uppercase(), it) }
                            }
                            // Media3 delivers MP4 `©lyr` as a text frame with the ID3 identifier.
                            "USLT" -> if (uslt == null) uslt = entry.values.firstOrNull()
                        }
                        is InternalFrame -> tags.putIfAbsent(entry.description.uppercase(), entry.text)
                        is BinaryFrame -> when (entry.id) {
                            "UFID" -> if (ufidRecording == null) ufidRecording = decodeUfid(entry.data)
                            "USLT" -> if (uslt == null) uslt = decodeUslt(entry.data)
                        }
                    }
                }
            }

            fun mbid(vararg keys: String) = keys.firstNotNullOfOrNull { key -> tags[key]?.let(::firstMbid) }
            return EmbeddedTags(
                recordingMbid = mbid("MUSICBRAINZ_TRACKID", "MUSICBRAINZ_RECORDINGID", "MUSICBRAINZ TRACK ID")
                    ?: ufidRecording?.let(::firstMbid),
                releaseMbid = mbid("MUSICBRAINZ_ALBUMID", "MUSICBRAINZ ALBUM ID"),
                artistMbid = mbid("MUSICBRAINZ_ARTISTID", "MUSICBRAINZ ARTIST ID"),
                albumArtistMbid = mbid("MUSICBRAINZ_ALBUMARTISTID", "MUSICBRAINZ ALBUM ARTIST ID"),
                lyrics = (tags["LYRICS"] ?: tags["UNSYNCEDLYRICS"] ?: tags["UNSYNCED LYRICS"] ?: uslt)
                    ?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        /**
         * Only a single entry is unambiguous: several (`a;b`) belong to several artists and don't match
         * the one name MediaStore provides.
         */
        private fun firstMbid(value: String): String? {
            val found = MBID.findAll(value).map { it.value.lowercase() }.toList()
            return found.singleOrNull()
        }

        /** `UFID`: owner, 0 byte, identifier. Only the MusicBrainz owner counts. */
        private fun decodeUfid(data: ByteArray): String? {
            val end = data.indexOf(0)
            if (end < 0) return null
            val owner = String(data, 0, end, Charsets.ISO_8859_1)
            if (owner != UFID_OWNER) return null
            return String(data, end + 1, data.size - end - 1, Charsets.ISO_8859_1)
        }

        /** `USLT`: encoding, language (3), description (terminated), text. */
        internal fun decodeUslt(data: ByteArray): String? {
            if (data.size < 5) return null
            val encoding = data[0].toInt()
            val charset: Charset = when (encoding) {
                0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> return null
            }
            val wide = encoding == 1 || encoding == 2
            var start = 4
            while (start < data.size) {
                if (wide) {
                    if (start + 1 < data.size && data[start].toInt() == 0 && data[start + 1].toInt() == 0) {
                        start += 2
                        break
                    }
                    start += 2
                } else {
                    if (data[start].toInt() == 0) {
                        start += 1
                        break
                    }
                    start += 1
                }
            }
            if (start >= data.size) return null
            return String(data, start, data.size - start, charset).trimEnd('\u0000')
        }
    }
}
