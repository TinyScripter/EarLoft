package de.kleinabersmart.earloft.core.datasource.jellyfin

import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.datasource.RemotePlaylistInfo
import de.kleinabersmart.earloft.core.datasource.local.FallbackNames
import de.kleinabersmart.earloft.core.model.LyricLine
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinCover
import de.kleinabersmart.earloft.core.network.jellyfin.JfItem
import de.kleinabersmart.earloft.core.network.jellyfin.JfLyrics
import de.kleinabersmart.earloft.core.network.jellyfin.JfNameId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Converts Jellyfin songs (`Audio`) into [RawTrack]s (concept 14). */
internal object JellyfinTrackMapper {

    private const val TICKS_PER_MS = 10_000L

    fun isMusic(item: JfItem): Boolean = item.type == "Audio"

    fun map(item: JfItem, names: FallbackNames): RawTrack {
        val ids = item.providerIds
        val artistNames = item.artistItems.map { it.name }.ifEmpty { item.artists }.mapNotNull { it.orBlankNull() }
        val artistDisplay = artistNames.joinToString(" & ").orBlankNull() ?: names.unknownArtist
        val artists = item.artistItems.toRawArtists(ids[MBID_ARTIST]).ifEmpty { listOf(RawArtist(artistDisplay)) }

        val declaredAlbumArtist = item.albumArtist.orBlankNull()
            ?: item.albumArtists.joinToString(" & ") { it.name }.orBlankNull()
        val isCompilation = FallbackNames.isVariousArtists(declaredAlbumArtist)
        val albumArtistDisplay = if (isCompilation) names.variousArtists else declaredAlbumArtist ?: artistDisplay
        val albumArtists = when {
            isCompilation -> listOf(RawArtist(names.variousArtists))
            else -> item.albumArtists.toRawArtists(ids[MBID_ALBUM_ARTIST]).ifEmpty { listOf(RawArtist(albumArtistDisplay)) }
        }

        val media = item.mediaSources.firstOrNull()
        return RawTrack(
            remoteId = item.id,
            title = item.name.orBlankNull() ?: names.unknownTitle,
            artistDisplay = artistDisplay,
            artists = artists,
            albumTitle = item.album.orBlankNull() ?: names.unknownAlbum,
            albumArtistDisplay = albumArtistDisplay,
            albumArtists = albumArtists,
            albumRemoteId = item.albumId,
            isCompilation = isCompilation,
            discNumber = item.parentIndexNumber?.takeIf { it > 0 } ?: 1,
            trackNumber = item.indexNumber?.takeIf { it > 0 } ?: 0,
            durationMs = (item.runTimeTicks ?: 0L) / TICKS_PER_MS,
            year = item.productionYear?.takeIf { it > 0 },
            genre = item.genres.firstOrNull().orBlankNull(),
            genres = item.genres.mapNotNull { it.orBlankNull() },
            // As with Subsonic: the recording and the release.
            mbidTrack = ids[MBID_RECORDING].orBlankNull(),
            mbidAlbum = ids[MBID_ALBUM].orBlankNull(),
            // The server measures loudness itself (LUFS scan); it doesn't provide peaks.
            rgTrackGainDb = item.normalizationGain,
            rgAlbumGainDb = item.albumNormalizationGain,
            coverRemoteId = coverRemoteId(item),
            mimeType = mimeTypeFor(media?.container ?: item.container),
            bitRate = media?.bitrate,
            sizeBytes = media?.size,
            lastModified = changeKey(item),
            dateAdded = item.dateCreated?.toEpochMillis(),
            starredAt = if (item.userData?.isFavorite == true) 0L else null,
        )
    }

    /**
     * The album cover, otherwise the song's own image; with an image tag so a new cover gets a new URL. Emby
     * never names the album's tag: if the song has no image of its own, the album stays without a tag (on the Emby test server 11 of
     * 1286 songs, whose albums all have an image; without an image the server responds with 404 = no cover).
     */
    private fun coverRemoteId(item: JfItem): String? {
        val albumId = item.albumId
        val albumTag = item.albumPrimaryImageTag
        return when {
            albumId != null && albumTag != null -> JellyfinCover.id(albumId, albumTag)
            item.imageTags.containsKey("Primary") -> JellyfinCover.id(item.id, item.imageTags["Primary"])
            albumId != null -> JellyfinCover.id(albumId, null)
            else -> null
        }
    }

    /**
     * As with Subsonic, a hash over all adopted fields as `lastModified`, without the favorite
     * (the app sets that itself as well; the sync checks it separately).
     */
    private fun changeKey(item: JfItem): Long {
        val media = item.mediaSources.firstOrNull()
        val fingerprint = listOf(
            item.name, item.album, item.albumId, item.albumArtist,
            item.artistItems.joinToString { it.name }, item.artists.joinToString(),
            item.albumArtists.joinToString { it.name },
            item.indexNumber, item.parentIndexNumber, item.runTimeTicks, item.productionYear, item.genres.joinToString(),
            item.providerIds.toSortedMap().toString(), item.normalizationGain, item.albumNormalizationGain,
            coverRemoteId(item), item.albumPrimaryImageTag, item.container, media?.container, media?.bitrate, media?.size,
        ).joinToString("\u0000")
        return hash(fingerprint)
    }

    /**
     * Jellyfin has no modification date for playlists; a hash over name and entries detects every change
     * on the server (conflict detection, concept 6.4).
     */
    fun playlistInfo(remoteId: String, name: String, itemIds: List<String>): RemotePlaylistInfo =
        RemotePlaylistInfo(remoteId = remoteId, name = name, changedAt = hash(name + "\u0000" + itemIds.joinToString(",")))

    /**
     * Synced if the server marks it so and timestamps are present; otherwise as plain text. An offset from the
     * server (from `[offset:]`) shifts all lines as with LRC and Subsonic: positive = earlier.
     */
    fun lyrics(dto: JfLyrics): Lyrics {
        val offsetMs = (dto.metadata?.offset ?: 0L) / TICKS_PER_MS
        val timed = dto.lyrics.mapNotNull { line ->
            line.start?.let { LyricLine((it / TICKS_PER_MS - offsetMs).coerceAtLeast(0), line.text) }
        }
        if (dto.metadata?.isSynced != false && timed.size == dto.lyrics.size && timed.any { it.text.isNotBlank() }) {
            return Lyrics.Synced(timed.sortedBy { it.startMs })
        }
        val text = dto.lyrics.joinToString("\n") { it.text }.trim()
        return if (text.isEmpty()) Lyrics.None else Lyrics.Plain(text)
    }

    /**
     * MIME type for the file's container. For MP4, Jellyfin names all variants (`mov,mp4,m4a,…`); unknown
     * containers stay `null`.
     */
    fun mimeTypeFor(container: String?): String? {
        val kinds = container?.lowercase()?.split(',')?.map { it.trim() }.orEmpty()
        return when {
            kinds.isEmpty() -> null
            "mp3" in kinds -> "audio/mpeg"
            "flac" in kinds -> "audio/flac"
            kinds.any { it == "ogg" || it == "oga" || it == "opus" } -> "audio/ogg"
            kinds.any { it == "m4a" || it == "mp4" || it == "m4b" } -> "audio/mp4"
            "aac" in kinds -> "audio/aac"
            "wav" in kinds -> "audio/wav"
            kinds.any { it == "aiff" || it == "aif" } -> "audio/aiff"
            "ape" in kinds -> "audio/x-ape"
            "wv" in kinds -> "audio/x-wavpack"
            kinds.any { it == "webm" || it == "webma" } -> "audio/webm"
            kinds.any { it == "wma" || it == "asf" } -> "audio/x-ms-wma"
            else -> null
        }
    }

    /** The source's MBID only applies if there is exactly one artist – otherwise it would be unclear whom it belongs to. */
    private fun List<JfNameId>.toRawArtists(singleMbid: String?): List<RawArtist> {
        val named = filter { it.name.isNotBlank() }
        return named.map {
            RawArtist(name = it.name.trim(), mbid = singleMbid.orBlankNull().takeIf { named.size == 1 }, remoteId = it.id.orBlankNull())
        }
    }

    private fun hash(text: String): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))).long

    private fun String?.orBlankNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    fun String.toEpochMillis(): Long? =
        try {
            OffsetDateTime.parse(this).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

    private const val MBID_RECORDING = "MusicBrainzRecording"
    private const val MBID_ALBUM = "MusicBrainzAlbum"
    private const val MBID_ARTIST = "MusicBrainzArtist"
    private const val MBID_ALBUM_ARTIST = "MusicBrainzAlbumArtist"
}
