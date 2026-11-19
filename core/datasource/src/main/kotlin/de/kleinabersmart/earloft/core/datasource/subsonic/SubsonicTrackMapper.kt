package de.kleinabersmart.earloft.core.datasource.subsonic

import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.datasource.RemotePlaylistInfo
import de.kleinabersmart.earloft.core.datasource.local.FallbackNames
import de.kleinabersmart.earloft.core.model.LyricLine
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.network.subsonic.dto.AlbumDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.ArtistRefDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.PlaylistDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.SongDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.StructuredLyricsDto
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Converts Subsonic songs into [RawTrack]s. */
internal object SubsonicTrackMapper {

    fun isMusic(song: SongDto): Boolean = !song.isDir && (song.type == null || song.type == "music")

    fun map(song: SongDto, album: AlbumDto?, names: FallbackNames): RawTrack {
        val artistDisplay = song.displayArtist.orBlankNull()
            ?: song.artist.orBlankNull()
            ?: song.artists.joinToString(" & ") { it.name }.orBlankNull()
            ?: names.unknownArtist
        val artists = song.artists.toRawArtists().ifEmpty { listOf(RawArtist(artistDisplay, remoteId = song.artistId.orBlankNull())) }

        val declaredAlbumArtist = song.displayAlbumArtist.orBlankNull()
            ?: album?.displayArtist.orBlankNull()
            ?: album?.artist.orBlankNull()
        val isCompilation = album?.isCompilation == true ||
            FallbackNames.isVariousArtists(declaredAlbumArtist)
        val albumArtistDisplay = when {
            isCompilation -> names.variousArtists
            else -> declaredAlbumArtist ?: artistDisplay
        }
        val albumArtists = when {
            isCompilation -> listOf(RawArtist(names.variousArtists))
            else -> song.albumArtists.toRawArtists().ifEmpty {
                listOf(RawArtist(albumArtistDisplay, remoteId = album?.artistId.orBlankNull()?.takeIf { declaredAlbumArtist != null }))
            }
        }

        val rg = song.replayGain
        return RawTrack(
            remoteId = song.id,
            title = song.title.orBlankNull() ?: names.unknownTitle,
            artistDisplay = artistDisplay,
            artists = artists,
            albumTitle = song.album.orBlankNull() ?: album?.name.orBlankNull() ?: names.unknownAlbum,
            albumArtistDisplay = albumArtistDisplay,
            albumArtists = albumArtists,
            albumRemoteId = song.albumId.orBlankNull() ?: album?.id,
            isCompilation = isCompilation,
            discNumber = song.discNumber?.takeIf { it > 0 } ?: 1,
            trackNumber = song.track?.takeIf { it > 0 } ?: 0,
            durationMs = (song.duration ?: 0) * 1000L,
            year = song.year?.takeIf { it > 0 } ?: album?.year?.takeIf { it > 0 },
            genre = song.genre.orBlankNull() ?: song.genres.firstNotNullOfOrNull { it.name.orBlankNull() },
            genres = song.genres.mapNotNull { it.name.orBlankNull() },
            mbidTrack = song.musicBrainzId.orBlankNull(),
            mbidAlbum = album?.musicBrainzId.orBlankNull(),
            rgTrackGainDb = rg?.trackGain,
            rgTrackPeak = rg?.trackPeak,
            rgAlbumGainDb = rg?.albumGain,
            rgAlbumPeak = rg?.albumPeak,
            coverRemoteId = album?.coverArt.orBlankNull() ?: song.coverArt.orBlankNull(),
            mimeType = song.contentType.orBlankNull(),
            bitRate = song.bitRate?.let { it * 1000 },
            sizeBytes = song.size,
            lastModified = changeKey(song, album),
            dateAdded = song.created?.toEpochMillis(),
            starredAt = song.starred?.let { it.toEpochMillis() ?: 0L },
        )
    }

    /**
     * Subsonic provides no modification date. Instead, a hash over all adopted fields serves
     * as `lastModified`: same value ⇒ nothing changed, the sync can skip the song.
     * The star isn't part of it – the app sets it itself as well (sync queue), and the sync checks it separately.
     */
    private fun changeKey(song: SongDto, album: AlbumDto?): Long {
        val fingerprint = listOf(
            song.title, song.album, song.artist, song.displayArtist, song.displayAlbumArtist,
            song.artists.joinToString { it.name + "/" + it.musicBrainzId },
            song.albumArtists.joinToString { it.name + "/" + it.musicBrainzId },
            song.track, song.discNumber, song.duration, song.year, song.genre, song.genres.joinToString { it.name }, song.musicBrainzId,
            song.replayGain, song.coverArt, song.contentType, song.bitRate, song.size,
            album?.musicBrainzId, album?.isCompilation, album?.coverArt, album?.year, album?.displayArtist,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("MD5").digest(fingerprint.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest).long
    }

    private fun List<ArtistRefDto>.toRawArtists(): List<RawArtist> =
        filter { it.name.isNotBlank() }
            .map { RawArtist(name = it.name, mbid = it.musicBrainzId.orBlankNull(), remoteId = it.id.orBlankNull()) }

    private fun String?.orBlankNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** If `changed` is missing, 0 applies – then a server change simply can't be detected. */
    fun playlistInfo(playlist: PlaylistDto): RemotePlaylistInfo =
        RemotePlaylistInfo(
            remoteId = playlist.id,
            name = playlist.name,
            changedAt = playlist.changed?.toEpochMillis() ?: 0L,
        )

    /**
     * Prefers synced lyrics. `synced` decides, not the presence of `start`. `offset` (ms) applies as in
     * the OpenSubsonic specification and with LRC: positive = lines appear earlier.
     */
    fun lyrics(structured: List<StructuredLyricsDto>): Lyrics {
        structured.firstOrNull { it.synced && it.line.any { line -> line.start != null } }?.let { entry ->
            val lines = entry.line
                .mapNotNull { line -> line.start?.let { LyricLine((it - entry.offset).coerceAtLeast(0), line.value) } }
                .sortedBy { it.startMs }
            if (lines.any { it.text.isNotBlank() }) return Lyrics.Synced(lines)
        }
        structured.firstOrNull { !it.synced }?.let { entry ->
            val text = entry.line.joinToString("\n") { it.value }.trim()
            if (text.isNotEmpty()) return Lyrics.Plain(text)
        }
        // Only synced lyrics without usable timestamps: show as text.
        structured.firstOrNull()?.let { entry ->
            val text = entry.line.joinToString("\n") { it.value }.trim()
            if (text.isNotEmpty()) return Lyrics.Plain(text)
        }
        return Lyrics.None
    }

    fun String.toEpochMillis(): Long? =
        try {
            OffsetDateTime.parse(this).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
}
