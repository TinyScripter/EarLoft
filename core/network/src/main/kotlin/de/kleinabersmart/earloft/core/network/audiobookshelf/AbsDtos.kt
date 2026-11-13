package de.kleinabersmart.earloft.core.network.audiobookshelf

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
    /** Zum Teilen über Open Library (Konzept 13); die ASIN bleibt bewusst weg. */
    val isbn: String? = null,
    /** Für die Buchseite; das Jahr kommt als Text („2014“). */
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
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
data class AbsMeResponse(
    /** Kennung des Kontos; die Sitzungen, die die App meldet, gehören diesem Konto. */
    val id: String = "",
    val mediaProgress: List<AbsMediaProgressDto> = emptyList(),
    val bookmarks: List<AbsBookmarkDto> = emptyList(),
)

/** Lesezeichen des Kontos; der Server erkennt es an Eintrag und [time] (Sekunden). */
@Serializable
data class AbsBookmarkDto(
    val libraryItemId: String,
    val time: Double,
    val title: String = "",
    /** Unix-ms. */
    val createdAt: Long = 0,
)

@Serializable
data class AbsBookmarkUpdate(val time: Double, val title: String)

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
    /** Aus „Weiterhören“ genommen; fällt auf dem Server mit der nächsten neuen Position. */
    val hideFromContinueListening: Boolean = false,
)

@Serializable
data class AbsProgressUpdate(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
    /** Immer mitgeschickt (ohne Standardwert, sonst fiele `false` weg); Audiobookshelf 2.37 übernimmt es per PATCH. */
    val hideFromContinueListening: Boolean,
)
