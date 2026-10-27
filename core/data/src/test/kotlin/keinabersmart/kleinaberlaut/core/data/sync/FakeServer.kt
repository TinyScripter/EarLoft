package keinabersmart.kleinaberlaut.core.data.sync

import android.graphics.Bitmap
import android.net.Uri
import keinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.datasource.RawTrack
import keinabersmart.kleinaberlaut.core.datasource.RemoteLibraryActions
import keinabersmart.kleinaberlaut.core.datasource.RemotePlaylist
import keinabersmart.kleinaberlaut.core.datasource.RemotePlaylistInfo
import keinabersmart.kleinaberlaut.core.datasource.SourceBatch
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Server im Speicher: liefert [tracks], merkt sich Aufrufe in [calls] und hält Playlists. */
internal class FakeServer(
    override val sourceId: String,
    var tracks: List<RawTrack> = emptyList(),
) : MusicDataSource, RemoteLibraryActions {
    override val type = SourceType.SUBSONIC
    override val displayName = sourceId
    val calls = mutableListOf<String>()
    var failure: Exception? = null
    val playlists = LinkedHashMap<String, RemotePlaylist>()
    private var clock = 1_000L
    private var nextId = 1

    /** Legt an oder ändert (z. B. „ein anderer Client“); jede Änderung bekommt ein neues `changedAt`. */
    fun putPlaylist(name: String, songRemoteIds: List<String>, remoteId: String = "pl${nextId++}"): String {
        playlists[remoteId] = RemotePlaylist(RemotePlaylistInfo(remoteId, name, clock++), songRemoteIds)
        return remoteId
    }

    override fun fullScan(): Flow<SourceBatch> = flowOf(SourceBatch(tracks))
    override suspend fun resolvePlayback(remoteId: String) = PlaybackLocation.LocalFile(Uri.EMPTY)
    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null

    override suspend fun setFavorite(remoteId: String, favorite: Boolean) {
        failure?.let { throw it }
        calls += "favorite $remoteId $favorite"
    }

    override suspend fun scrobble(remoteId: String, playedAt: Long) {
        failure?.let { throw it }
        calls += "scrobble $remoteId $playedAt"
    }

    override suspend fun playlists(): List<RemotePlaylistInfo> {
        failure?.let { throw it }
        return playlists.values.map { it.info }
    }

    override suspend fun playlist(remoteId: String): RemotePlaylist {
        failure?.let { throw it }
        return playlists[remoteId] ?: throw SubsonicException(SubsonicException.NOT_FOUND, "Playlist not found")
    }

    override suspend fun createPlaylist(name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        failure?.let { throw it }
        calls += "create $name $songRemoteIds"
        return playlists.getValue(putPlaylist(name, songRemoteIds)).info
    }

    override suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        failure?.let { throw it }
        val remoteId = current.info.remoteId
        calls += "replace $remoteId $name $songRemoteIds"
        return playlists.getValue(putPlaylist(name, songRemoteIds, remoteId)).info
    }

    override suspend fun deletePlaylist(remoteId: String) {
        failure?.let { throw it }
        calls += "delete $remoteId"
        playlists.remove(remoteId) ?: throw SubsonicException(SubsonicException.NOT_FOUND, "Playlist not found")
    }
}

/** Lokale Quelle: nur Tracks, keine Server-Funktionen. */
internal class FakeLocalSource(private val tracks: List<RawTrack>) : MusicDataSource {
    override val sourceId = "local"
    override val type = SourceType.LOCAL
    override val displayName = "local"
    override fun fullScan(): Flow<SourceBatch> = flowOf(SourceBatch(tracks))
    override suspend fun resolvePlayback(remoteId: String) = PlaybackLocation.LocalFile(Uri.EMPTY)
    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null
}

internal class FixedSources(private vararg val sources: MusicDataSource) : MusicDataSources {
    override suspend fun all(): List<MusicDataSource> = sources.toList()
    override suspend fun get(sourceId: String): MusicDataSource? = sources.firstOrNull { it.sourceId == sourceId }
}

internal object NoScheduler : SyncScheduler {
    override suspend fun enqueue() = Unit
}

internal fun KleinaberlautDatabase.syncQueueProcessor(vararg sources: MusicDataSource) = SyncQueueProcessor(
    database = this,
    syncDao = syncDao(),
    playlistDao = playlistDao(),
    playlistSyncer = playlistSyncer(),
    sources = FixedSources(*sources),
)

internal fun KleinaberlautDatabase.playlistSyncer() = PlaylistSyncer(this, playlistDao(), syncDao())

internal fun KleinaberlautDatabase.librarySyncer(scheduler: SyncScheduler = NoScheduler) = LibrarySyncer(
    database = this,
    sourceDao = sourceDao(),
    mergeDao = mergeDao(),
    libraryDao = libraryDao(),
    mergeResolver = MergeResolver(mergeDao(), syncDao()),
    registry = FixedSources(),
    cleanupListeners = emptySet(),
    syncQueue = SyncQueueStarter(syncDao(), scheduler),
    playlistSyncer = playlistSyncer(),
)
