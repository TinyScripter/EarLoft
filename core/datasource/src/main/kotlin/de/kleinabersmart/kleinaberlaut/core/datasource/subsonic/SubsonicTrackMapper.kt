package de.kleinabersmart.kleinaberlaut.core.datasource.subsonic

import de.kleinabersmart.kleinaberlaut.core.datasource.RawArtist
import de.kleinabersmart.kleinaberlaut.core.datasource.RawTrack
import de.kleinabersmart.kleinaberlaut.core.datasource.RemotePlaylistInfo
import de.kleinabersmart.kleinaberlaut.core.datasource.local.FallbackNames
import de.kleinabersmart.kleinaberlaut.core.model.LyricLine
import de.kleinabersmart.kleinaberlaut.core.model.Lyrics
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.dto.AlbumDto
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.dto.ArtistRefDto
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.dto.PlaylistDto
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.dto.SongDto
import de.kleinabersmart.kleinaberlaut.core.network.subsonic.dto.StructuredLyricsDto
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Wandelt Subsonic-Songs in [RawTrack]s um. */
internal object SubsonicTrackMapper {

    /** Navidrome und andere Server benennen Sampler so; lokal heißt der Album-Künstler [FallbackNames.variousArtists]. */
    private val VARIOUS_ARTISTS_NAMES = setOf("various artists", "various", "va")

    fun isMusic(song: SongDto): Boolean = !song.isDir && (song.type == null || song.type == "music")

    fun map(song: SongDto, album: AlbumDto?, names: FallbackNames): RawTrack {
        val artistDisplay = song.displayArtist.orBlankNull()
            ?: song.artist.orBlankNull()
            ?: song.artists.joinToString(" & ") { it.name }.orBlankNull()
            ?: names.unknownArtist
        val artists = song.artists.toRawArtists().ifEmpty { listOf(RawArtist(artistDisplay)) }

        val declaredAlbumArtist = song.displayAlbumArtist.orBlankNull()
            ?: album?.displayArtist.orBlankNull()
            ?: album?.artist.orBlankNull()
        val isCompilation = album?.isCompilation == true ||
            declaredAlbumArtist?.lowercase() in VARIOUS_ARTISTS_NAMES
        val albumArtistDisplay = when {
            isCompilation -> names.variousArtists
            else -> declaredAlbumArtist ?: artistDisplay
        }
        val albumArtists = when {
            isCompilation -> listOf(RawArtist(names.variousArtists))
            else -> song.albumArtists.toRawArtists().ifEmpty { listOf(RawArtist(albumArtistDisplay)) }
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
            isCompilation = isCompilation,
            discNumber = song.discNumber?.takeIf { it > 0 } ?: 1,
            trackNumber = song.track?.takeIf { it > 0 } ?: 0,
            durationMs = (song.duration ?: 0) * 1000L,
            year = song.year?.takeIf { it > 0 } ?: album?.year?.takeIf { it > 0 },
            genre = song.genre.orBlankNull(),
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
     * Subsonic liefert kein Änderungsdatum. Stattdessen dient ein Hash über alle übernommenen Felder
     * als `lastModified`: Gleicher Wert ⇒ nichts geändert, der Abgleich kann den Song überspringen.
     * Der Stern gehört nicht dazu – ihn setzt auch die App selbst (Sync-Queue), der Abgleich prüft ihn getrennt.
     */
    private fun changeKey(song: SongDto, album: AlbumDto?): Long {
        val fingerprint = listOf(
            song.title, song.album, song.artist, song.displayArtist, song.displayAlbumArtist,
            song.artists.joinToString { it.name + "/" + it.musicBrainzId },
            song.albumArtists.joinToString { it.name + "/" + it.musicBrainzId },
            song.track, song.discNumber, song.duration, song.year, song.genre, song.musicBrainzId,
            song.replayGain, song.coverArt, song.contentType, song.bitRate, song.size,
            album?.musicBrainzId, album?.isCompilation, album?.coverArt, album?.year, album?.displayArtist,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("MD5").digest(fingerprint.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest).long
    }

    private fun List<ArtistRefDto>.toRawArtists(): List<RawArtist> =
        filter { it.name.isNotBlank() }.map { RawArtist(name = it.name, mbid = it.musicBrainzId.orBlankNull()) }

    private fun String?.orBlankNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** Fehlt `changed`, gilt 0 – dann lässt sich nur keine Server-Änderung erkennen. */
    fun playlistInfo(playlist: PlaylistDto): RemotePlaylistInfo =
        RemotePlaylistInfo(
            remoteId = playlist.id,
            name = playlist.name,
            changedAt = playlist.changed?.toEpochMillis() ?: 0L,
        )

    /**
     * Bevorzugt synchronisierte Lyrics. `synced` entscheidet, nicht das Vorhandensein von `start`;
     * `offset` wird ignoriert (Vorzeichen-Konvention uneinheitlich, in der Praxis meist 0).
     */
    fun lyrics(structured: List<StructuredLyricsDto>): Lyrics {
        structured.firstOrNull { it.synced && it.line.any { line -> line.start != null } }?.let { entry ->
            val lines = entry.line.mapNotNull { line -> line.start?.let { LyricLine(it, line.value) } }.sortedBy { it.startMs }
            if (lines.any { it.text.isNotBlank() }) return Lyrics.Synced(lines)
        }
        structured.firstOrNull { !it.synced }?.let { entry ->
            val text = entry.line.joinToString("\n") { it.value }.trim()
            if (text.isNotEmpty()) return Lyrics.Plain(text)
        }
        // Nur synchronisierte Lyrics ohne verwertbare Zeitstempel: als Text anzeigen.
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
