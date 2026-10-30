package keinabersmart.kleinaberlaut.wear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.util.Log
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.repository.AlbumColorRepository
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteAlbumDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteLibraryDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemotePlaylistDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteStateDto
import keinabersmart.kleinaberlaut.core.wearsync.hashesAt
import keinabersmart.kleinaberlaut.core.wearsync.putAssetPayload
import keinabersmart.kleinaberlaut.core.wearsync.putPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält für die Fernsteuerung von der Uhr aus (Wear-Konzept 15) drei DataItems aktuell: was das Handy spielt, dessen
 * Cover sowie Alben und Playlists. Geschrieben wird nur, solange eine Uhr mit der App gekoppelt ist.
 */
@Singleton
class WearRemotePublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val watchConnection: WatchConnection,
    private val playback: PlaybackConnection,
    private val artwork: ArtworkRepository,
    private val albumColors: AlbumColorRepository,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val favorites: FavoritesRepository,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Album, dessen Cover zuletzt geschrieben wurde; ändert sich nur beim Albumwechsel. */
    private var publishedCoverAlbumId: Long? = null

    /** Zuletzt geschriebener Bibliotheksindex; `null` = noch beim Data Layer nachsehen. */
    private var publishedLibraryHash: String? = null

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            watchConnection.watchAppInstalled.flatMapLatest { installed ->
                if (!installed) {
                    emptyFlow()
                } else {
                    // Zustandsänderungen und Sprünge (Spulen) zusammenfassen: Ein Titelwechsel löst beides aus.
                    merge(playback.state.map { }, playback.positionJumps).debounce(STATE_DEBOUNCE_MS)
                }
            }.collect { publishState() }
        }
        scope.launch {
            watchConnection.watchAppInstalled.flatMapLatest { installed ->
                if (!installed) {
                    emptyFlow()
                } else {
                    combine(library.albums(), playlists.playlists(), favorites.favorites()) { albums, lists, favs ->
                        WearRemoteLibraryDto(
                            albums = albums.map { WearRemoteAlbumDto(it.id, it.title, it.artist, it.songCount) },
                            playlists = lists.map { WearRemotePlaylistDto(it.id, it.name, it.songCount) },
                            favoritesCount = favs.size,
                        )
                    }.debounce(LIBRARY_DEBOUNCE_MS)
                }
            }.collect { publishLibrary(it) }
        }
    }

    private suspend fun publishState() {
        val state = playback.state.value
        val current = state.current
        val position = withContext(Dispatchers.Main) { playback.currentPositionMs }
        val albumId = current?.albumId
        val dto = WearRemoteStateDto(
            hasMedia = current != null,
            title = current?.title.orEmpty(),
            artist = current?.artist.orEmpty(),
            albumId = albumId,
            albumTitle = current?.albumTitle.orEmpty(),
            durationMs = state.durationMs,
            positionMs = position,
            sampledAt = System.currentTimeMillis(),
            isPlaying = state.isPlaying,
            shuffle = state.shuffle,
            seedColor = albumId?.let { albumColors.seedColorNow(it) },
            volume = musicVolume(),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
        dataClient.putPayload(WearPaths.REMOTE_STATE, WearCodec.encode(dto))
        if (albumId != null && albumId != publishedCoverAlbumId) publishCover(albumId)
    }

    private suspend fun publishCover(albumId: Long) {
        val bitmap = artwork.albumCover(albumId, COVER_SIZE_PX)
        val jpeg = bitmap?.let { withContext(Dispatchers.Default) { it.toJpeg() } } ?: ByteArray(0)
        if (dataClient.putPayload(WearPaths.REMOTE_COVER, WearCodec.encodeCover(albumId, jpeg))) publishedCoverAlbumId = albumId
    }

    /** Nur bei geändertem Inhalt – ein Abgleich ändert die Bibliothek oft, ohne dass sich etwas Sichtbares ändert. */
    private suspend fun publishLibrary(dto: WearRemoteLibraryDto) {
        val hash = WearCodec.hash(WearCodec.encode(dto))
        if (publishedLibraryHash == null) publishedLibraryHash = dataClient.hashesAt(WearPaths.REMOTE_LIBRARY).values.firstOrNull()
        if (hash == publishedLibraryHash) return
        if (dataClient.putAssetPayload(WearPaths.REMOTE_LIBRARY, WearCodec.encodeCompressed(dto), hash)) {
            publishedLibraryHash = hash
            Log.d(TAG, "Fernsteuerung: ${dto.albums.size} Alben, ${dto.playlists.size} Playlists")
        }
    }

    private fun musicVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 0f
    }

    private fun Bitmap.toJpeg(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    private companion object {
        const val TAG = "WearRemotePublisher"
        const val STATE_DEBOUNCE_MS = 250L
        const val LIBRARY_DEBOUNCE_MS = 3_000L

        /** Die Uhr zeigt das Cover im Kreis von rund 400 px; 300 px bleiben mit JPEG deutlich unter der 100-KB-Grenze. */
        const val COVER_SIZE_PX = 300
        const val JPEG_QUALITY = 80
    }
}
