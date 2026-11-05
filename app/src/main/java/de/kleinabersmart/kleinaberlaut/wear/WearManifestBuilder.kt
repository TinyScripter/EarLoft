package de.kleinabersmart.kleinaberlaut.wear

import de.kleinabersmart.kleinaberlaut.core.data.wear.WearContent
import de.kleinabersmart.kleinaberlaut.core.data.wear.WearSongContent
import de.kleinabersmart.kleinaberlaut.core.model.WearSelectionKind
import de.kleinabersmart.kleinaberlaut.core.wearsync.PhoneFileDto
import de.kleinabersmart.kleinaberlaut.core.wearsync.ReplayGainDto
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearManifest
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearSelectionDto
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearServerSourceDto
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearSongDto

/**
 * Bildet die aufgelösten Auswahlen auf das Manifest für die Uhr ab (Wear-Konzept 4.1). Songs
 * verweisen per `key` (bevorzugte `sourceUid` am Handy) aufeinander, nicht per `songId`.
 */
object WearManifestBuilder {

    fun build(content: WearContent, favoritesTitle: String): WearManifest {
        val keys = content.songs.associate { it.songId to it.key }
        return WearManifest(
            selections = content.selections.map { selection ->
                WearSelectionDto(
                    key = selection.key,
                    kind = selection.kind,
                    title = if (selection.kind == WearSelectionKind.FAVORITES) favoritesTitle else selection.title,
                    songKeys = selection.songIds.mapNotNull { keys[it] },
                )
            },
            songs = content.songs.map { it.toDto() },
        )
    }

    private fun WearSongContent.toDto() = WearSongDto(
        key = key,
        title = title,
        artists = artists,
        artistDisplay = artistDisplay,
        album = album,
        albumArtist = albumArtist,
        isCompilation = isCompilation,
        discNumber = discNumber,
        trackNumber = trackNumber,
        durationMs = durationMs,
        year = year,
        genre = genre,
        mbid = mbid,
        albumMbid = albumMbid,
        replayGain = replayGain?.let { ReplayGainDto(it.trackGainDb, it.trackPeak, it.albumGainDb, it.albumPeak) },
        serverSources = serverSources.map {
            WearServerSourceDto(it.uid, it.sourceId, it.remoteId, it.mimeType, it.sizeBytes, it.bitRate, it.coverRemoteId)
        },
        phoneFile = phoneFile?.let { PhoneFileDto(it.uid, it.sizeBytes, it.mimeType) },
    )
}
