package de.kleinabersmart.earloft.core.data.wear

import de.kleinabersmart.earloft.core.model.ReplayGain
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind

/**
 * What should go to the watch, resolved on the phone (Wear concept 4.1/5): the selections with their songs in
 * order and each song once with everything the watch needs for downloading. The app builds
 * the manifest for the Data Layer from it.
 */
data class WearContent(
    val selections: List<WearSelectionContent> = emptyList(),
    val songs: List<WearSongContent> = emptyList(),
)

data class WearSelectionContent(
    val key: String,
    val kind: WearSelectionKind,
    val refId: Long?,
    /** Album or playlist name; empty for favorites (the UI sets the name). */
    val title: String,
    /** Order of the selection; duplicates allowed for playlists. */
    val songIds: List<Long>,
)

data class WearSongContent(
    val songId: Long,
    /** Preferred `sourceUid` on the phone (local before server, then as during playback). */
    val key: String,
    val title: String,
    val artists: List<String>,
    val artistDisplay: String,
    val album: String,
    val albumArtist: String,
    val isCompilation: Boolean,
    val discNumber: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val mbid: String?,
    val albumMbid: String?,
    val replayGain: ReplayGain?,
    /** Server sources in the order the phone prefers them. */
    val serverSources: List<WearServerSource>,
    /** `null` = the phone has no file (neither local nor downloaded). */
    val phoneFile: WearPhoneFile?,
)

data class WearServerSource(
    val uid: String,
    val sourceId: String,
    val remoteId: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val bitRate: Int?,
    val coverRemoteId: String?,
)

/** A file on the phone (local or finished download) that it can send to the watch. */
data class WearPhoneFile(
    val uid: String,
    val sizeBytes: Long?,
    val mimeType: String?,
)

/** A server as the watch mirrors it – without password (Wear concept 6). */
data class MirroredServer(
    val sourceId: String,
    /** Always a server type ([SourceType.isServer]). */
    val type: SourceType,
    val name: String,
    val baseUrl: String,
    val username: String,
    val priority: Int,
    val formPost: Boolean,
    /**
     * Changes with the credentials on the phone (new password, signed in again), but reveals nothing about them; `null` if
     * the phone has none. The watch then requests anew instead of only noticing from a server rejection that its own are outdated.
     */
    val credentialsStamp: String? = null,
)
