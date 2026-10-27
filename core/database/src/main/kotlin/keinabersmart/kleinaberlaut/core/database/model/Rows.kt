package keinabersmart.kleinaberlaut.core.database.model

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
    val sourceId: String,
    val remoteId: String,
    val coverRemoteId: String?,
    val sourceType: SourceType,
    val sourcePriority: Int,
    val mimeType: String?,
    val bitRate: Int?,
)
