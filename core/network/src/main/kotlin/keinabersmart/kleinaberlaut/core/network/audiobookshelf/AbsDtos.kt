package keinabersmart.kleinaberlaut.core.network.audiobookshelf

import kotlinx.serialization.Serializable

// Nur die Felder, die die App braucht; Unbekanntes (u. a. das Anmelde-Token in /api/me) wird ignoriert.

@Serializable
data class AbsLibrariesResponse(val libraries: List<AbsLibraryDto> = emptyList())

@Serializable
data class AbsLibraryDto(
    val id: String,
    val name: String = "",
    /** `book` oder `podcast`. */
    val mediaType: String = "book",
)

@Serializable
data class AbsAuthorsResponse(val authors: List<AbsAuthorDto> = emptyList())

@Serializable
data class AbsAuthorDto(
    val id: String,
    val name: String = "",
    /** Pfad des Bilds auf dem Server; `null`, wenn der Autor keines hat. */
    val imagePath: String? = null,
)

@Serializable
data class AbsItemsPage(
    val results: List<AbsItemDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class AbsItemDto(
    val id: String,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val isMissing: Boolean = false,
    val isInvalid: Boolean = false,
    val media: AbsMediaDto,
)

@Serializable
data class AbsMediaDto(
    val metadata: AbsMetadataDto,
    val duration: Double = 0.0,
    val numChapters: Int = 0,
    val numAudioFiles: Int = 0,
    /** Nur mit `expanded=1`. */
    val chapters: List<AbsChapterDto> = emptyList(),
    val audioFiles: List<AbsAudioFileDto> = emptyList(),
)

@Serializable
data class AbsMetadataDto(
    val title: String = "",
    val subtitle: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
    val description: String? = null,
)

@Serializable
data class AbsChapterDto(
    val id: Int = 0,
    val start: Double,
    val end: Double,
    val title: String = "",
)

@Serializable
data class AbsAudioFileDto(
    val index: Int = 0,
    val ino: String,
    val duration: Double = 0.0,
    val exclude: Boolean = false,
    val invalid: Boolean = false,
)

@Serializable
data class AbsMeResponse(val mediaProgress: List<AbsMediaProgressDto> = emptyList())

@Serializable
data class AbsMediaProgressDto(
    val libraryItemId: String,
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    /** Unix-ms der letzten Änderung auf dem Server. */
    val lastUpdate: Long = 0,
)

@Serializable
data class AbsProgressUpdate(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
)
