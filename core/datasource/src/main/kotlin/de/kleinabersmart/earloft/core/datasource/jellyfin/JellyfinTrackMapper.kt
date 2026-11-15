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

/** Wandelt Jellyfin-Songs (`Audio`) in [RawTrack]s um (Konzept 14). */
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
            // Wie bei Subsonic: die Aufnahme (Recording) und die Veröffentlichung (Release).
            mbidTrack = ids[MBID_RECORDING].orBlankNull(),
            mbidAlbum = ids[MBID_ALBUM].orBlankNull(),
            // Die Lautheit misst der Server selbst (LUFS-Scan); Peaks liefert er nicht.
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
     * Das Albumcover, sonst das eigene Bild des Songs; mit Bild-Tag, damit ein neues Cover eine neue URL bekommt. Emby
     * nennt den Tag des Albums nie: Hat der Song kein eigenes Bild, bleibt das Album ohne Tag (am Emby-Testserver 11 von
     * 1286 Songs, deren Alben alle ein Bild haben; ohne Bild antwortet der Server mit 404 = kein Cover).
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
     * Wie bei Subsonic ein Hash über alle übernommenen Felder als `lastModified`, ohne den Favoriten
     * (den setzt auch die App selbst; der Abgleich prüft ihn getrennt).
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
     * Jellyfin kennt kein Änderungsdatum für Playlists; ein Hash über Name und Einträge erkennt jede Änderung
     * auf dem Server (Konflikterkennung, Konzept 6.4).
     */
    fun playlistInfo(remoteId: String, name: String, itemIds: List<String>): RemotePlaylistInfo =
        RemotePlaylistInfo(remoteId = remoteId, name = name, changedAt = hash(name + "\u0000" + itemIds.joinToString(",")))

    /**
     * Synchronisiert, wenn der Server es so kennzeichnet und Zeitstempel da sind; sonst als Text. Ein Offset des
     * Servers (aus `[offset:]`) verschiebt wie bei LRC und Subsonic alle Zeilen: positiv = früher.
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
     * MIME-Typ zum Behälter der Datei. Jellyfin nennt bei MP4 alle Varianten (`mov,mp4,m4a,…`); unbekannte
     * Behälter bleiben `null`.
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

    /** Die MBID der Quelle gilt nur, wenn es genau einen Künstler gibt – sonst wäre unklar, zu wem sie gehört. */
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
