package de.kleinabersmart.earloft.core.network.audiobookshelf

import kotlinx.serialization.Serializable

// Only the fields the app needs; unknown ones (among others the login token in /api/me) are ignored.

@Serializable
data class AbsLibrariesResponse(val libraries: List<AbsLibraryDto> = emptyList())

@Serializable
data class AbsLibraryDto(
    val id: String,
    val name: String = "",
    /** `book` or `podcast`. */
    val mediaType: String = "book",
    /** Library folders on the server; an upload ends up in one of them (`books_improvements.md` M9). */
    val folders: List<AbsFolderDto> = emptyList(),
)

@Serializable
data class AbsFolderDto(val id: String, val fullPath: String = "")

@Serializable
data class AbsAuthorsResponse(val authors: List<AbsAuthorDto> = emptyList())

@Serializable
data class AbsAuthorDto(
    val id: String,
    val name: String = "",
    /** Path of the image on the server; `null` if the author has none. */
    val imagePath: String? = null,
    /** Description of the author (maintained or taken over from Audible via "Match"). */
    val description: String? = null,
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
    /** Only with `expanded=1`. */
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
    /** For sharing via Open Library (concept 13); the ASIN is deliberately left out. */
    val isbn: String? = null,
    /** For the book page; the year comes as text ("2014"). */
    val publishedYear: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    /** May contain entries with multiple genres ("Polizei, Thriller"). */
    val genres: List<String> = emptyList(),
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
    /** ID of the account; the sessions the app reports belong to this account. */
    val id: String = "",
    val mediaProgress: List<AbsMediaProgressDto> = emptyList(),
    val bookmarks: List<AbsBookmarkDto> = emptyList(),
    /** `root`, `admin` or `user`; administrators may always upload. */
    val type: String = "",
    val permissions: AbsPermissionsDto = AbsPermissionsDto(),
) {
    /** May the account upload books (`POST /api/upload`)? */
    val canUpload: Boolean get() = type == "root" || type == "admin" || permissions.upload
}

@Serializable
data class AbsPermissionsDto(val upload: Boolean = false)

/** Bookmarks of the account; the server identifies one by entry and [time] (seconds). */
@Serializable
data class AbsBookmarkDto(
    val libraryItemId: String,
    val time: Double,
    val title: String = "",
    /** Unix ms. */
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
    /** Unix ms of the last change on the server. */
    val lastUpdate: Long = 0,
    /** Removed from "Continue listening"; cleared on the server with the next new position. */
    val hideFromContinueListening: Boolean = false,
)

@Serializable
data class AbsProgressUpdate(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
    /** Always sent (without a default value, otherwise `false` would be omitted); Audiobookshelf 2.37 applies it via PATCH. */
    val hideFromContinueListening: Boolean,
)
