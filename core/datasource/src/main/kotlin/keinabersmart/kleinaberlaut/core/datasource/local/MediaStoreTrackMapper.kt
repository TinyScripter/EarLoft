package keinabersmart.kleinaberlaut.core.datasource.local

import keinabersmart.kleinaberlaut.core.datasource.RawArtist
import keinabersmart.kleinaberlaut.core.datasource.RawTrack

/** Eine Zeile aus `MediaStore.Audio.Media`, so roh wie möglich. */
internal data class MediaStoreRow(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val albumArtist: String?,
    val isCompilation: Boolean,
    val durationMs: Long,
    /** MediaStore kodiert Disc und Track als `disc * 1000 + track`. */
    val track: Int,
    val year: Int,
    val genre: String?,
    val mimeType: String?,
    val bitRate: Int?,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
)

internal data class FallbackNames(
    val unknownTitle: String,
    val unknownArtist: String,
    val unknownAlbum: String,
    val variousArtists: String,
)

/**
 * Wandelt MediaStore-Zeilen in [RawTrack]s um.
 *
 * Fehlt der Album-Künstler-Tag, wird er aus den Track-Künstlern abgeleitet: Haben alle Tracks
 * eines MediaStore-Albums denselben Künstler, ist er der Album-Künstler, sonst
 * „Verschiedene Künstler“. So zerfallen Sampler ohne Album-Künstler-Tag nicht in Einzelalben.
 */
internal object MediaStoreTrackMapper {

    private const val UNKNOWN = "<unknown>"

    fun map(rows: List<MediaStoreRow>, names: FallbackNames): List<RawTrack> {
        val artistsPerAlbum = rows.groupBy { it.albumId }
            .mapValues { (_, albumRows) -> albumRows.map { it.artist.orUnknown(names.unknownArtist) }.toSet() }

        return rows.map { row ->
            val artist = row.artist.orUnknown(names.unknownArtist)
            val taggedAlbumArtist = row.albumArtist.cleaned()
            val albumArtist = when {
                taggedAlbumArtist != null -> taggedAlbumArtist
                row.isCompilation -> names.variousArtists
                artistsPerAlbum.getValue(row.albumId).size > 1 -> names.variousArtists
                else -> artist
            }
            RawTrack(
                remoteId = row.id.toString(),
                title = row.title.orUnknown(names.unknownTitle),
                artistDisplay = artist,
                artists = listOf(RawArtist(artist)),
                albumTitle = row.album.orUnknown(names.unknownAlbum),
                albumArtistDisplay = albumArtist,
                albumArtists = listOf(RawArtist(albumArtist)),
                isCompilation = row.isCompilation || albumArtist == names.variousArtists,
                discNumber = (row.track / 1000).coerceAtLeast(1),
                trackNumber = row.track % 1000,
                durationMs = row.durationMs,
                year = row.year.takeIf { it > 0 },
                genre = row.genre.cleaned(),
                coverRemoteId = row.id.toString(),
                mimeType = row.mimeType,
                bitRate = row.bitRate,
                sizeBytes = row.sizeBytes,
                lastModified = row.dateModifiedSec * 1000,
                dateAdded = row.dateAddedSec * 1000,
            )
        }
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN }

    private fun String?.orUnknown(fallback: String): String = cleaned() ?: fallback
}
