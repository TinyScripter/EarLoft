package de.kleinabersmart.earloft.core.datasource.jellyfin

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.datasource.ExternalPlayback
import de.kleinabersmart.earloft.core.datasource.IncompleteScanException
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.datasource.RemoteLibraryActions
import de.kleinabersmart.earloft.core.datasource.RemoteLyrics
import de.kleinabersmart.earloft.core.datasource.RemotePlaybackReporting
import de.kleinabersmart.earloft.core.datasource.RemotePlaylist
import de.kleinabersmart.earloft.core.datasource.RemotePlaylistInfo
import de.kleinabersmart.earloft.core.datasource.RemoteQuickSync
import de.kleinabersmart.earloft.core.datasource.RemoteStarred
import de.kleinabersmart.earloft.core.datasource.RemoteSimilarSongs
import de.kleinabersmart.earloft.core.datasource.RemoteTranscoding
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.datasource.decodeSampled
import de.kleinabersmart.earloft.core.datasource.jellyfin.JellyfinTrackMapper.toEpochMillis
import de.kleinabersmart.earloft.core.datasource.local.FallbackNames
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.LrcParser
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinFlavor
import de.kleinabersmart.earloft.core.network.jellyfin.JellyfinServer
import de.kleinabersmart.earloft.core.network.jellyfin.JfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A Jellyfin or Emby server (concept 14); only its music libraries. */
class JellyfinDataSource internal constructor(
    val server: JellyfinServer,
    override val displayName: String,
    private val api: JellyfinApi,
    private val names: FallbackNames,
) : MusicDataSource, RemoteLibraryActions, RemoteLyrics, RemoteQuickSync, ExternalPlayback, RemoteTranscoding,
    RemotePlaybackReporting, RemoteSimilarSongs {

    override val sourceId: String get() = server.sourceId
    override val type: SourceType = server.flavor.sourceType

    /** Playlist → may this account edit it, and when it was asked (`nanoTime`). */
    private val editable = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** Playlist → `Etag` and its entries. */
    private val playlistItems = ConcurrentHashMap<String, CachedItems>()

    private class CachedItems(val etag: String, val ids: List<String>, val fetchedAt: Long)

    /**
     * Library by library in pages up to the reported total; errors abort the scan so the sync
     * deletes nothing. If a proxy shortens the pages, it continues with the next one; if a page stays empty before the end,
     * songs are missing, and the scan aborts instead of deleting them locally.
     */
    override fun fullScan(): Flow<SourceBatch> = flow {
        api.musicLibraries(server).forEach { library ->
            // If the library shifted along the way (scan or deletion on the server), start over once.
            if (!scanLibrary(library) && !scanLibrary(library)) {
                throw IncompleteScanException(library.name ?: library.id, "${library.name} changed during the sync")
            }
        }
    }

    /**
     * From the second page on, each request starts one song earlier: it must be the last one of the previous page. Otherwise
     * something was dropped before the offset, and the first song of the page would be missing – the sync would delete it
     * along with playlist entries, history and favorite. @return `false` on a shift.
     */
    private suspend fun FlowCollector<SourceBatch>.scanLibrary(library: JfItem): Boolean {
        var start = 0
        var lastId: String? = null
        while (true) {
            val overlap = if (lastId == null) 0 else 1
            val result = api.songs(server, library.id, start - overlap, PAGE_SIZE + overlap)
            if (lastId != null && result.items.firstOrNull()?.id != lastId) return false
            val page = result.items.drop(overlap)
            val tracks = page.filter(JellyfinTrackMapper::isMusic).map { JellyfinTrackMapper.map(it, names) }
            if (tracks.isNotEmpty()) emit(SourceBatch(tracks))
            start += page.size
            lastId = page.lastOrNull()?.id ?: lastId
            val total = result.totalRecordCount.takeIf { it > 0 }
                // Without a total, as before: a short page is the last one.
                ?: if (page.size < PAGE_SIZE) return true else continue
            if (start >= total) return true
            if (page.isEmpty()) throw IncompleteScanException(library.name ?: library.id, "Jellyfin delivered only $start of $total songs from ${library.name}")
        }
    }

    /**
     * Newest first, like the full sync only from the music libraries (otherwise the next full sync would delete again
     * what came from other libraries here). Per library it stops at the first older song.
     */
    override suspend fun tracksAddedSince(since: Long): List<RawTrack> {
        val tracks = mutableListOf<RawTrack>()
        api.musicLibraries(server).forEach { library ->
            var start = 0
            while (start < MAX_NEW_SONGS) {
                val page = api.newestSongs(server, library.id, start, NEWEST_PAGE_SIZE).items
                // Without a timestamp, better to load (the merge detects unchanged items).
                val fresh = page.takeWhile { item -> item.dateCreated?.toEpochMillis()?.let { it > since } ?: true }
                fresh.filter(JellyfinTrackMapper::isMusic).mapTo(tracks) { JellyfinTrackMapper.map(it, names) }
                if (fresh.size < page.size || page.size < NEWEST_PAGE_SIZE) break
                start += page.size
            }
        }
        return tracks
    }

    /** Jellyfin doesn't remember since when something has been a favorite. */
    override suspend fun starred(): RemoteStarred {
        val byType = api.favoriteMusicItems(server).groupBy({ it.type }, { it.id to 0L })
        return RemoteStarred(
            songs = byType["Audio"].orEmpty().toMap(),
            albums = byType["MusicAlbum"].orEmpty().toMap(),
            artists = byType["MusicArtist"].orEmpty().toMap(),
        )
    }

    /** The mix discards unknown items (e.g. from other libraries) anyway. */
    override suspend fun similarSongs(remoteId: String, count: Int): List<String> =
        api.instantMix(server, remoteId, count).filter { it != remoteId }

    override suspend fun resolvePlayback(remoteId: String): PlaybackLocation =
        PlaybackLocation.Remote(uri = api.streamUrl(server, remoteId).toString().toUri(), cacheKey = "$sourceId:$remoteId")

    override fun transcodedPlayback(remoteId: String, codec: String, maxKbps: Int): PlaybackLocation.Remote =
        PlaybackLocation.Remote(
            uri = api.transcodedStreamUrl(server, remoteId, codec, maxKbps).toString().toUri(),
            cacheKey = "$sourceId:$remoteId:$codec-$maxKbps",
        )

    override fun externalStreamUrl(remoteId: String, transcodeTo: Pair<String, Int>?): String {
        val url = if (transcodeTo == null) {
            api.streamUrl(server, remoteId)
        } else {
            api.transcodedStreamUrl(server, remoteId, transcodeTo.first, transcodeTo.second)
        }
        return api.withToken(server, url).toString()
    }

    /** Jellyfin and Emby deliver images without authentication; the token stays out of the URL for the receiver. */
    override fun externalCoverUrl(coverRemoteId: String, sizePx: Int): String =
        api.imageUrl(server, coverRemoteId, sizePx).toString()

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? {
        val bytes = api.image(server, remoteId, sizePx) ?: return null
        return withContext(Dispatchers.Default) { decodeSampled(bytes, sizePx) }
    }

    /** Songs, albums and artists are entries of the same call. */
    override suspend fun setFavorite(remoteId: String, favorite: Boolean, kind: FavoriteKind) =
        api.setFavorite(server, remoteId, favorite)

    /**
     * Submitted later: "played" with a timestamp. The server's scrobble plugins only see this if they listen to changed
     * user data (ListenBrainz: mode `UserDataSaved`); live it goes through [reportPlayback] (concept 14).
     */
    override suspend fun scrobble(remoteId: String, playedAt: Long) = api.markPlayed(server, remoteId, playedAt)

    override suspend fun reportPlayback(remoteId: String, durationMs: Long) = api.reportPlayback(server, remoteId, durationMs)

    /**
     * Only music playlists the account may change. On every sync (including the quick one after startup) that would be two
     * requests per visible playlist, even per foreign public one: hence the edit permission is remembered for [EDIT_CHECK_MS]
     * and the entries are only refetched when the `Etag` has changed. The change hash stays the same (name and entries).
     */
    override suspend fun playlists(): List<RemotePlaylistInfo> =
        api.playlists(server)
            .filter { it.mediaType == null || it.mediaType == "Audio" }
            .filter { canEdit(it.id) }
            .map { JellyfinTrackMapper.playlistInfo(it.id, it.name.orEmpty(), itemIds(it)) }

    private suspend fun canEdit(playlistId: String): Boolean {
        val now = System.nanoTime()
        editable[playlistId]?.takeIf { (now - it.second) / NANOS_PER_MS < EDIT_CHECK_MS }?.let { return it.first }
        return api.canEditPlaylist(server, playlistId).also { editable[playlistId] = it to now }
    }

    /**
     * Jellyfin 12.4 changes a playlist's `Etag` with name and entries, including on reordering. Emby 4.10 changes it on
     * adding, removing and renaming, but not on reordering (both measured): there the entries are valid for at most
     * [EMBY_ITEMS_MAX_AGE_MS], so a reordering in another program arrives after that at the latest.
     */
    private suspend fun itemIds(playlist: JfItem): List<String> {
        val etag = playlist.etag
        val now = System.nanoTime()
        playlistItems[playlist.id]
            ?.takeIf { etag != null && it.etag == etag }
            ?.takeIf { server.flavor == JellyfinFlavor.JELLYFIN || (now - it.fetchedAt) / NANOS_PER_MS < EMBY_ITEMS_MAX_AGE_MS }
            ?.let { return it.ids }
        return api.playlistItemIds(server, playlist.id).also { ids ->
            if (etag != null) playlistItems[playlist.id] = CachedItems(etag, ids, now)
        }
    }

    override suspend fun playlist(remoteId: String): RemotePlaylist {
        val ids = api.playlistItemIds(server, remoteId)
        val name = api.item(server, remoteId).name.orEmpty()
        return RemotePlaylist(JellyfinTrackMapper.playlistInfo(remoteId, name, ids), ids)
    }

    override suspend fun createPlaylist(name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        val remoteId = api.createPlaylist(server, name, songRemoteIds)
        if (server.flavor == JellyfinFlavor.JELLYFIN) createdAt[remoteId] = System.nanoTime()
        // Don't read back: if that failed, the next attempt would create a second playlist. If the server differs,
        // the next sync fetches its state.
        return JellyfinTrackMapper.playlistInfo(remoteId, name, songRemoteIds)
    }

    /** Jellyfin replaces name and entries in one call, Emby in several. */
    override suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        awaitSettled(current.info.remoteId)
        // A reordering of our own doesn't change the `Etag` on Emby: the remembered entries are outdated afterwards and would
        // count as a change on the server during the next sync.
        playlistItems.remove(current.info.remoteId)
        api.updatePlaylist(server, current.info.remoteId, name, songRemoteIds)
        return playlist(current.info.remoteId).info
    }

    override suspend fun deletePlaylist(remoteId: String) {
        playlistItems.remove(remoteId)
        api.deleteItem(server, remoteId)
    }

    override suspend fun lyrics(remoteId: String, artist: String, title: String): Lyrics = when (server.flavor) {
        JellyfinFlavor.JELLYFIN -> api.lyrics(server, remoteId)?.let(JellyfinTrackMapper::lyrics) ?: Lyrics.None
        JellyfinFlavor.EMBY -> api.embyLyricsText(server, remoteId)?.let(LrcParser::parse) ?: Lyrics.None
    }

    /**
     * Jellyfin processes a new playlist once more shortly after it is created and resets name and entries to the
     * state at creation (on the test server with 12.4: a change right after was lost, after 2 s no longer). The sync queue
     * coalesces offline creations and changes into one creation; affected is a change right after
     * creation. Emby 4.10 resets nothing (measured), so there is no waiting there.
     */
    private suspend fun awaitSettled(remoteId: String) {
        val created = createdAt.remove(remoteId) ?: return
        val waitMs = CREATION_SETTLE_MS - (System.nanoTime() - created) / NANOS_PER_MS
        if (waitMs > 0) delay(waitMs)
    }

    internal companion object {
        const val PAGE_SIZE = 500
        const val NEWEST_PAGE_SIZE = 100
        const val MAX_NEW_SONGS = 1000
        const val CREATION_SETTLE_MS = 10_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val EDIT_CHECK_MS = 6 * 60 * 60_000L
        private const val EMBY_ITEMS_MAX_AGE_MS = 30 * 60_000L

        /** Per process: the data source is recreated when the servers are reloaded, the playlist stays fresh. */
        private val createdAt = ConcurrentHashMap<String, Long>()
    }
}

@Singleton
class JellyfinDataSourceFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: JellyfinApi,
) {
    fun create(server: JellyfinServer, displayName: String): JellyfinDataSource =
        JellyfinDataSource(server, displayName, api, FallbackNames.from(context))
}
