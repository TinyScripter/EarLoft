package de.kleinabersmart.earloft.core.data.sync

import androidx.test.core.app.ApplicationProvider
import android.graphics.Bitmap
import android.net.Uri
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.datasource.RemoteLibraryActions
import de.kleinabersmart.earloft.core.datasource.RemoteLyrics
import de.kleinabersmart.earloft.core.datasource.RemotePlaylist
import de.kleinabersmart.earloft.core.datasource.RemotePlaylistInfo
import de.kleinabersmart.earloft.core.datasource.RemoteQuickSync
import de.kleinabersmart.earloft.core.datasource.RemoteStarred
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory server: returns [tracks], records calls in [calls] and holds playlists. */
internal class FakeServer(
    override val sourceId: String,
    var tracks: List<RawTrack> = emptyList(),
) : MusicDataSource, RemoteLibraryActions, RemoteLyrics, RemoteQuickSync {
    override val type = SourceType.SUBSONIC
    override val displayName = sourceId
    val calls = mutableListOf<String>()
    var failure: Exception? = null

    /** Only the playlists fail (an account without access to them). */
    var playlistFailure: Exception? = null

    /** Runs when a playlist is being replaced, before anything happens (e.g. a change in the app meanwhile). */
    var whileReplacing: (suspend () -> Unit)? = null
    val playlists = LinkedHashMap<String, RemotePlaylist>()
    val lyrics = HashMap<String, Lyrics>()
    /** For the quick sync: new albums and favorites (`remoteId` → timestamp). */
    var newTracks: List<RawTrack> = emptyList()
    var starred: Map<String, Long> = emptyMap()
    var starredAlbums: Map<String, Long> = emptyMap()
    var starredArtists: Map<String, Long> = emptyMap()
    var lastSince = -1L
    private var clock = 1_000L
    private var nextId = 1

    /** Creates or modifies (e.g. "another client"); every change gets a new `changedAt`. */
    fun putPlaylist(name: String, songRemoteIds: List<String>, remoteId: String = "pl${nextId++}"): String {
        playlists[remoteId] = RemotePlaylist(RemotePlaylistInfo(remoteId, name, clock++), songRemoteIds)
        return remoteId
    }

    override fun fullScan(): Flow<SourceBatch> = flowOf(SourceBatch(tracks))
    override suspend fun resolvePlayback(remoteId: String) = PlaybackLocation.LocalFile(Uri.EMPTY)
    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? = null

    override suspend fun setFavorite(remoteId: String, favorite: Boolean, kind: FavoriteKind) {
        failure?.let { throw it }
        calls += if (kind == FavoriteKind.SONG) "favorite $remoteId $favorite" else "favorite ${kind.name.lowercase()} $remoteId $favorite"
    }

    override suspend fun scrobble(remoteId: String, playedAt: Long) {
        failure?.let { throw it }
        calls += "scrobble $remoteId $playedAt"
    }

    override suspend fun playlists(): List<RemotePlaylistInfo> {
        failure?.let { throw it }
        playlistFailure?.let { throw it }
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
        whileReplacing?.invoke()
        failure?.let { throw it }
        val remoteId = current.info.remoteId
        calls += "replace $remoteId $name $songRemoteIds"
        return playlists.getValue(putPlaylist(name, songRemoteIds, remoteId)).info
    }

    override suspend fun tracksAddedSince(since: Long): List<RawTrack> {
        failure?.let { throw it }
        lastSince = since
        return newTracks
    }

    override suspend fun starred(): RemoteStarred {
        failure?.let { throw it }
        return RemoteStarred(starred, starredAlbums, starredArtists)
    }

    override suspend fun lyrics(remoteId: String, artist: String, title: String): Lyrics {
        failure?.let { throw it }
        calls += "lyrics $remoteId"
        return lyrics[remoteId] ?: Lyrics.None
    }

    override suspend fun deletePlaylist(remoteId: String) {
        failure?.let { throw it }
        calls += "delete $remoteId"
        playlists.remove(remoteId) ?: throw SubsonicException(SubsonicException.NOT_FOUND, "Playlist not found")
    }
}

/** Local source: tracks only, no server features. */
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

internal fun EarLoftDatabase.syncQueueProcessor(
    vararg sources: MusicDataSource,
    notices: SyncNotices = SyncNotices(),
) = SyncQueueProcessor(
    database = this,
    syncDao = syncDao(),
    playlistDao = playlistDao(),
    playlistSyncer = playlistSyncer(notices),
    sources = FixedSources(*sources),
    notices = notices,
)

internal fun EarLoftDatabase.playlistSyncer(notices: SyncNotices = SyncNotices()) =
    PlaylistSyncer(this, playlistDao(), syncDao(), notices)

internal fun EarLoftDatabase.librarySyncer(
    scheduler: SyncScheduler = NoScheduler,
    notices: SyncNotices = SyncNotices(),
) = LibrarySyncer(
    database = this,
    sourceDao = sourceDao(),
    mergeDao = mergeDao(),
    libraryDao = libraryDao(),
    mergeResolver = MergeResolver(mergeDao(), syncDao(), CollectionSourceResolver(mergeDao(), syncDao())),
    changeListeners = emptySet(),
    syncQueue = SyncQueueStarter(syncDao(), scheduler),
    playlistSyncer = playlistSyncer(notices),
    syncDao = syncDao(),
    sweepGuard = SweepGuard(ApplicationProvider.getApplicationContext()),
)

/**
 * Syncs [source]; creates a server beforehand, like the server form does ([LibrarySyncer] only creates the local
 * source itself, so that a server that was just removed doesn't come back).
 */
internal suspend fun LibrarySyncer.syncAdding(db: EarLoftDatabase, source: MusicDataSource) {
    if (source.type.isServer) db.sourceDao().insertIfAbsent(SourceEntity(source.sourceId, source.type, source.displayName))
    sync(source)
}
