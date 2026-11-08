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
 * Eine Quelle der Uhr, deren „Bibliothek“ der Teil des Manifests ist, der zu ihr gehört
 * (Wear-Konzept 7.1). So schreibt der vorhandene `LibrarySyncer` das Manifest in die Datenbank:
 * Mark-and-Sweep löscht, was aus allen Auswahlen fällt; der `MergeResolver` führt die Server-Quellen
 * eines Songs wieder zu einem Song zusammen.
 */
class WearManifestSource(
    override val sourceId: String,
    override val type: SourceType,
    private val tracks: List<RawTrack>,
) : MusicDataSource {
    override val displayName: String = sourceId

    override fun fullScan(): Flow<SourceBatch> = flowOf(SourceBatch(tracks))

    /** Abgespielt wird über die Quellen der `DataSourceRegistry` bzw. Downloads, nie über diese Quelle. */
    override suspend fun resolvePlayback(remoteId: String): PlaybackLocation =
        throw UnsupportedOperationException("Manifest-Quelle $sourceId spielt nichts ab")

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null

    companion object {
        /** Pseudo-Quelle für Songs, die nur das Handy hat (Typ [SourceType.PHONE]). */
        const val PHONE_SOURCE_ID = "phone"
    }
}

/**
 * Die Tracks des Manifests je Quelle: je bekanntem Server ein Track pro Server-Quelle des Songs, Songs
 * ohne (bekannte) Server-Quelle einmal unter [WearManifestSource.PHONE_SOURCE_ID] mit der `sourceUid`
 * des Handys als `remoteId` (z. B. `phone:local:123`).
 */
fun manifestTracks(manifest: WearManifest, servers: Set<String>, keepPhone: Set<String> = emptySet()): Map<String, List<RawTrack>> {
    val result = HashMap<String, MutableList<RawTrack>>()
    for (song in manifest.songs) {
        val version = song.version()
        val known = song.serverSources.filter { it.sourceId in servers }
        val phoneId = phoneRemoteId(song)
        // Schon übers Handy geladen ([keepPhone]): bleibt auch unter `phone`, wenn die Uhr den Server inzwischen kennt
        // (Update, neuer Server am Handy). Sonst nähme der Abgleich die Quelle samt Download und Datei mit, und der
        // Song käme erst wieder vom Server – per WLAN, meist erst am Ladegerät.
        if (known.isEmpty() || phoneId in keepPhone) {
            result.getOrPut(WearManifestSource.PHONE_SOURCE_ID) { mutableListOf() } += song.toRawTrack(phoneId, null, version)
        }
        known.forEach { result.getOrPut(it.sourceId) { mutableListOf() } += song.toRawTrack(it.remoteId, it, version) }
    }
    return result
}

/** Die `sourceUid`s, unter denen [song] auf der Uhr liegt (siehe [manifestTracks]). */
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
    // Wie der Hash einer Server-Quelle: Ändert sich am Song nichts, überspringt der Abgleich ihn.
    lastModified = version,
)

/** Stabiler Fingerabdruck des Songs (64 Bit des SHA-256 über das JSON). */
private fun WearSongDto.version(): Long =
    ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(WearCodec.encode(this))).long
