package de.kleinabersmart.earloft.core.datasource.local

import android.content.Context
import de.kleinabersmart.earloft.core.datasource.R
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack

/** A row from `MediaStore.Audio.Media`, as raw as possible. */
internal data class MediaStoreRow(
    val id: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val albumArtist: String?,
    val isCompilation: Boolean,
    val durationMs: Long,
    /** MediaStore encodes disc and track as `disc * 1000 + track`. */
    val track: Int,
    val year: Int,
    val genre: String?,
    val mimeType: String?,
    val bitRate: Int?,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    /** Storage volume (Android 10 and later), see [VolumeWatch]. */
    val volume: String? = null,
)

internal data class FallbackNames(
    val unknownTitle: String,
    val unknownArtist: String,
    val unknownAlbum: String,
    val variousArtists: String,
) {
    companion object {
        /** This is how servers name compilations; locally the album artist is called [variousArtists]. */
        private val VARIOUS_ARTISTS_NAMES = setOf("various artists", "various", "va")

        fun isVariousArtists(name: String?): Boolean = name?.trim()?.lowercase() in VARIOUS_ARTISTS_NAMES

        fun from(context: Context) = FallbackNames(
            unknownTitle = context.getString(R.string.unknown_title),
            unknownArtist = context.getString(R.string.unknown_artist),
            unknownAlbum = context.getString(R.string.unknown_album),
            variousArtists = context.getString(R.string.various_artists),
        )
    }
}

/**
 * Converts MediaStore rows into [RawTrack]s.
 *
 * If the album artist tag is missing, it is derived from the track artists: if all tracks
 * of a MediaStore album have the same artist, that is the album artist, otherwise
 * "Various artists". This way compilations without an album artist tag don't fall apart into single albums.
 */
internal object MediaStoreTrackMapper {

    private const val UNKNOWN = "<unknown>"

    /**
     * `lastModified` only has second resolution; the last digits mark the version of the tag reading.
     * Whoever adopts more from the tags increments the number – then all files are reread once.
     */
    const val TAGS_VERSION = 1L

    /** Adds to [track] what only the file itself knows. */
    fun applyTags(track: RawTrack, tags: EmbeddedTags): RawTrack = track.copy(
        mbidTrack = tags.recordingMbid,
        mbidAlbum = tags.releaseMbid,
        artists = track.artists.singleOrNull()?.copy(mbid = tags.artistMbid)?.let(::listOf) ?: track.artists,
        albumArtists = track.albumArtists.singleOrNull()?.copy(mbid = tags.albumArtistMbid)?.let(::listOf)
            ?: track.albumArtists,
    )

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
                lastModified = row.dateModifiedSec * 1000 + TAGS_VERSION,
                dateAdded = row.dateAddedSec * 1000,
            )
        }
    }

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN }

    private fun String?.orUnknown(fallback: String): String = cleaned() ?: fallback
}
