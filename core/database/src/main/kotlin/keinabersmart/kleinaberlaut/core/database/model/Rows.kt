package keinabersmart.kleinaberlaut.core.database.model

import keinabersmart.kleinaberlaut.core.model.DownloadState
import keinabersmart.kleinaberlaut.core.model.SourceType

/** Projektion für Song-Listen (Song + Albumtitel). */
data class SongRow(
    val songId: Long,
    val title: String,
    val artistDisplay: String,
    val albumId: Long,
    val albumTitle: String,
    val durationMs: Long,
    val discNumber: Int,
    val trackNumber: Int,
    val isFavorite: Boolean,
)

data class AlbumRow(
    val albumId: Long,
    val title: String,
    val albumArtistDisplay: String,
    val year: Int?,
    val songCount: Int,
)

data class ArtistRow(
    val artistId: Long,
    val name: String,
    val albumCount: Int,
)

/** Eine Song-Quelle samt Eigenschaften ihrer Quelle – Grundlage der Quellenwahl beim Abspielen. */
data class PlayableSourceRow(
    val uid: String,
    val songId: Long,
    val sourceId: String,
    val remoteId: String,
    val coverRemoteId: String?,
    val sourceType: SourceType,
    val sourcePriority: Int,
    val mimeType: String?,
    val bitRate: Int?,
)

/** Offline-Verfügbarkeit eines Songs: lokale Quelle und ggf. Download (`state == null`: keiner). */
data class SongOfflineRow(
    val songId: Long,
    val isLocal: Boolean,
    val state: DownloadState?,
    val bytesDone: Long?,
    val bytesTotal: Long?,
)

/** Ein Album mit mindestens einem Download. */
data class AlbumDownloadRow(
    val albumId: Long,
    val title: String,
    val albumArtistDisplay: String,
    val year: Int?,
    val songCount: Int,
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val doneBytes: Long,
)

/** Summen über alle Downloads. */
data class DownloadTotalsRow(
    val doneCount: Int,
    val pendingCount: Int,
    val failedCount: Int,
    val doneBytes: Long,
    val runningBytesDone: Long,
    val runningBytesTotal: Long,
)
