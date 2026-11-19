package de.kleinabersmart.earloft.wear.sync

import android.graphics.Bitmap
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RawArtist
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearManifest
import de.kleinabersmart.earloft.core.wearsync.WearServerSourceDto
import de.kleinabersmart.earloft.core.wearsync.WearSongDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A watch source whose "library" is the part of the manifest that belongs to it
 * (Wear concept 7.1). This way the existing `LibrarySyncer` writes the manifest to the database:
 * mark-and-sweep deletes whatever drops out of all selections; the `MergeResolver` merges the server sources
 * of a song back into one song.
 */
class WearManifestSource(
    override val sourceId: String,
    override val type: SourceType,
    private val tracks: List<RawTrack>,
) : MusicDataSource {
    override val displayName: String = sourceId

    override fun fullScan(): Flow<SourceBatch> = flowOf(SourceBatch(tracks))

    /** Playback goes through the sources of the `DataSourceRegistry` or downloads, never through this source. */
    override suspend fun resolvePlayback(remoteId: String): PlaybackLocation =
        throw UnsupportedOperationException("Manifest-Quelle $sourceId spielt nichts ab")

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null

    companion object {
        /** Pseudo source for songs only the phone has (type [SourceType.PHONE]). */
        const val PHONE_SOURCE_ID = "phone"
    }
}

/**
 * The manifest's tracks per source: for each known server one track per server source of the song; songs
 * without a (known) server source once under [WearManifestSource.PHONE_SOURCE_ID] with the phone's `sourceUid`
 * as `remoteId` (e.g. `phone:local:123`).
 */
fun manifestTracks(manifest: WearManifest, servers: Set<String>, keepPhone: Set<String> = emptySet()): Map<String, List<RawTrack>> {
    val result = HashMap<String, MutableList<RawTrack>>()
    for (song in manifest.songs) {
        val version = song.version()
        val known = song.serverSources.filter { it.sourceId in servers }
        val phoneId = phoneRemoteId(song)
        // Already downloaded via the phone ([keepPhone]): stays under `phone` even if the watch knows the server by now
        // (update, new server on the phone). Otherwise the sync would take the source along with download and file, and the
        // song would only come from the server again – over Wi-Fi, usually only on the charger.
        if (known.isEmpty() || phoneId in keepPhone) {
            result.getOrPut(WearManifestSource.PHONE_SOURCE_ID) { mutableListOf() } += song.toRawTrack(phoneId, null, version)
        }
        known.forEach { result.getOrPut(it.sourceId) { mutableListOf() } += song.toRawTrack(it.remoteId, it, version) }
    }
    return result
}

/** The `sourceUid`s under which [song] is stored on the watch (see [manifestTracks]). */
fun watchSourceUids(song: WearSongDto, servers: Set<String>): List<String> =
    song.serverSources.filter { it.sourceId in servers }.map { it.uid }
        .ifEmpty { listOf("${WearManifestSource.PHONE_SOURCE_ID}:${phoneRemoteId(song)}") }

private fun phoneRemoteId(song: WearSongDto): String = song.phoneFile?.uid ?: song.key

private fun WearSongDto.toRawTrack(remoteId: String, server: WearServerSourceDto?, version: Long) = RawTrack(
    remoteId = remoteId,
    title = title,
    artistDisplay = artistDisplay,
    artists = artists.map { RawArtist(it) },
    albumTitle = album,
    albumArtistDisplay = albumArtist,
    albumArtists = listOf(RawArtist(albumArtist)),
    isCompilation = isCompilation,
    discNumber = discNumber,
    trackNumber = trackNumber,
    durationMs = durationMs,
    year = year,
    genre = genre,
    mbidTrack = mbid,
    mbidAlbum = albumMbid,
    rgTrackGainDb = replayGain?.trackGainDb,
    rgTrackPeak = replayGain?.trackPeak,
    rgAlbumGainDb = replayGain?.albumGainDb,
    rgAlbumPeak = replayGain?.albumPeak,
    coverRemoteId = server?.coverRemoteId,
    mimeType = server?.mimeType ?: phoneFile?.mimeType,
    bitRate = server?.bitRate,
    sizeBytes = server?.sizeBytes ?: phoneFile?.sizeBytes,
    // Like the hash of a server source: if nothing changes on the song, the sync skips it.
    lastModified = version,
)

/** Stable fingerprint of the song (64 bits of the SHA-256 over the JSON). */
private fun WearSongDto.version(): Long =
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(WearCodec.encode(this))).long
