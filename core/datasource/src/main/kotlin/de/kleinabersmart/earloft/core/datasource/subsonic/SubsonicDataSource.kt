package de.kleinabersmart.earloft.core.datasource.subsonic

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.datasource.ExternalPlayback
import de.kleinabersmart.earloft.core.datasource.IncompleteScanException
import de.kleinabersmart.earloft.core.datasource.MusicDataSource
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RemoteLibraryActions
import de.kleinabersmart.earloft.core.datasource.RemoteLyrics
import de.kleinabersmart.earloft.core.datasource.RawTrack
import de.kleinabersmart.earloft.core.datasource.RemotePlaylist
import de.kleinabersmart.earloft.core.datasource.RemotePlaylistInfo
import de.kleinabersmart.earloft.core.datasource.RemoteQuickSync
import de.kleinabersmart.earloft.core.datasource.RemoteStarred
import de.kleinabersmart.earloft.core.datasource.RemoteSimilarSongs
import de.kleinabersmart.earloft.core.datasource.RemoteTranscoding
import de.kleinabersmart.earloft.core.datasource.SourceBatch
import de.kleinabersmart.earloft.core.datasource.decodeSampled
import de.kleinabersmart.earloft.core.datasource.local.FallbackNames
import de.kleinabersmart.earloft.core.datasource.subsonic.SubsonicTrackMapper.toEpochMillis
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicApi
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicCredentials
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicException
import de.kleinabersmart.earloft.core.network.subsonic.SubsonicServer
import de.kleinabersmart.earloft.core.network.subsonic.authenticatedUrl
import de.kleinabersmart.earloft.core.network.subsonic.dto.AlbumDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A Navidrome or Subsonic server (concept 6.3). */
class SubsonicDataSource internal constructor(
    val server: SubsonicServer,
    override val displayName: String,
    private val api: SubsonicApi,
    private val names: FallbackNames,
) : MusicDataSource, RemoteLibraryActions, RemoteLyrics, RemoteQuickSync, ExternalPlayback, RemoteTranscoding,
    RemoteSimilarSongs {

    override val sourceId: String get() = server.sourceId
    override val type: SourceType = SourceType.SUBSONIC

    /**
     * Album metadata (MBID, compilation flag, cover) comes from `getAlbumList2`, the songs from
     * `search3` with an empty query. Servers that return no songs for that are queried album by
     * album. Errors abort the scan so the sync deletes nothing.
     */
    override fun fullScan(): Flow<SourceBatch> = flow {
        val albums = loadAllAlbums()
        val received = scanSongs(albums) ?: scanSongs(albums)
            ?: throw IncompleteScanException(displayName, "$displayName changed during the sync")

        // A server that caps the page size or stops early would otherwise make the sweep delete the rest (review G5).
        val expected = albums.values.sumOf { it.songCount ?: 0 }
        if (received in 1 until expected / 2) {
            throw IncompleteScanException(displayName, "$displayName delivered only $received of $expected songs")
        }

        if (received == 0 && albums.isNotEmpty()) {
            albums.values.forEach { summary ->
                val album = api.album(server, summary.id)
                val tracks = album.song.filter(SubsonicTrackMapper::isMusic)
                    .map { SubsonicTrackMapper.map(it, album, names) }
                if (tracks.isNotEmpty()) emit(SourceBatch(tracks))
            }
        }
    }

    /**
     * All songs via `search3`, like Jellyfin's scan: from the second page on, each request starts one song earlier, and that
     * song must be the last one of the previous page. Otherwise something was dropped before the offset and the first song of
     * the page would never be read – the sweep would delete it. Only an empty page ends the scan, so a server that delivers
     * fewer songs per request than asked for is read completely.
     * @return the number of songs received, `null` on a shift.
     */
    private suspend fun FlowCollector<SourceBatch>.scanSongs(albums: Map<String, AlbumDto>): Int? {
        var offset = 0
        var lastId: String? = null
        while (true) {
            val overlap = if (lastId == null) 0 else 1
            val result = api.searchAllSongs(server, offset - overlap, PAGE_SIZE + overlap)
            // At the end of the list only the overlap song comes back; without it, something before the offset was removed.
            if (lastId != null && result.firstOrNull()?.id != lastId) return null
            val page = result.drop(overlap)
            if (page.isEmpty()) return offset
            val tracks = page.filter(SubsonicTrackMapper::isMusic)
                .map { SubsonicTrackMapper.map(it, albums[it.albumId], names) }
            if (tracks.isNotEmpty()) emit(SourceBatch(tracks))
            offset += page.size
            lastId = page.last().id
        }
    }

    /**
     * `getAlbumList2?type=newest` is sorted by creation time descending, on Navidrome with `RecentlyAddedByModTime` by
     * modification time: a retagged old album can then come before the really new ones. So older albums are skipped, not
     * a reason to stop (review G52); it stops after a page that ends with an older album.
     */
    override suspend fun tracksAddedSince(since: Long): List<RawTrack> {
        val tracks = mutableListOf<RawTrack>()
        var offset = 0
        // Without a timestamp, better to load (the merge detects unchanged items).
        fun AlbumDto.isFresh() = created?.toEpochMillis()?.let { it > since } ?: true
        while (offset < MAX_NEW_ALBUMS) {
            val page = api.albumList(server, "newest", offset, NEWEST_PAGE_SIZE)
            page.filter { it.isFresh() }.forEach { summary ->
                val album = api.album(server, summary.id)
                album.song.filter(SubsonicTrackMapper::isMusic).mapTo(tracks) { SubsonicTrackMapper.map(it, album, names) }
            }
            if (page.size < NEWEST_PAGE_SIZE || !page.last().isFresh()) break
            offset += page.size
        }
        return tracks
    }

    override suspend fun starred(): RemoteStarred {
        val starred = api.starred(server)
        return RemoteStarred(
            songs = starred.song.associate { it.id to it.starred.starredMillis() },
            albums = starred.album.associate { it.id to it.starred.starredMillis() },
            artists = starred.artist.associate { it.id to it.starred.starredMillis() },
        )
    }

    private fun String?.starredMillis(): Long = this?.toEpochMillis() ?: 0L

    override suspend fun similarSongs(remoteId: String, count: Int): List<String> =
        api.similarSongs(server, remoteId, count).filter(SubsonicTrackMapper::isMusic).map { it.id }

    override suspend fun resolvePlayback(remoteId: String): PlaybackLocation =
        PlaybackLocation.Remote(
            uri = api.streamUrl(server, remoteId).toString().toUri(),
            cacheKey = "$sourceId:$remoteId",
        )

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
        return server.authenticatedUrl(url).toString()
    }

    override fun externalCoverUrl(coverRemoteId: String, sizePx: Int): String =
        server.authenticatedUrl(api.coverArtUrl(server, coverRemoteId, sizePx)).toString()

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? {
        val bytes = api.coverArt(server, remoteId, sizePx) ?: return null
        return withContext(Dispatchers.Default) { decodeSampled(bytes, sizePx) }
    }

    override suspend fun setFavorite(remoteId: String, favorite: Boolean, kind: FavoriteKind) {
        if (favorite) api.star(server, remoteId, kind) else api.unstar(server, remoteId, kind)
    }

    override suspend fun scrobble(remoteId: String, playedAt: Long) = api.scrobble(server, remoteId, playedAt)

    /** Foreign (public) and smart playlists can't be changed and are skipped. */
    override suspend fun playlists(): List<RemotePlaylistInfo> {
        val username = (server.credentials as? SubsonicCredentials.Password)?.username
        return api.playlists(server)
            .filter { !it.readonly && (username == null || it.owner == null || it.owner.equals(username, ignoreCase = true)) }
            .map(SubsonicTrackMapper::playlistInfo)
    }

    override suspend fun playlist(remoteId: String): RemotePlaylist {
        val playlist = api.playlist(server, remoteId)
        return RemotePlaylist(SubsonicTrackMapper.playlistInfo(playlist), playlist.entry.map { it.id })
    }

    override suspend fun createPlaylist(name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        val chunks = songRemoteIds.chunked(chunkSize)
        val remoteId = api.createPlaylist(server, name, chunks.firstOrNull().orEmpty())
        chunks.drop(1).forEach { api.updatePlaylist(server, remoteId, null, emptyList(), it) }
        return SubsonicTrackMapper.playlistInfo(api.playlist(server, remoteId))
    }

    override suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        val remoteId = current.info.remoteId
        // Remove in descending order: with several calls the remaining indices stay valid.
        val removals = current.songRemoteIds.indices.reversed().chunked(chunkSize)
        val additions = songRemoteIds.chunked(chunkSize)
        val calls = removals.map { it to emptyList<String>() } + additions.map { emptyList<Int>() to it }
        if (server.supportsFormPost || calls.size <= 1) {
            api.updatePlaylist(server, remoteId, name, removals.flatten(), songRemoteIds)
        } else {
            calls.forEachIndexed { index, (remove, add) ->
                api.updatePlaylist(server, remoteId, name.takeIf { index == 0 }, remove, add)
            }
        }
        return SubsonicTrackMapper.playlistInfo(api.playlist(server, remoteId))
    }

    override suspend fun deletePlaylist(remoteId: String) = api.deletePlaylist(server, remoteId)

    /** OpenSubsonic first, then the classic `getLyrics` call (text only). */
    override suspend fun lyrics(remoteId: String, artist: String, title: String): Lyrics {
        SubsonicTrackMapper.lyrics(api.lyricsBySongId(server, remoteId)).let { if (it != Lyrics.None) return it }
        val text = try {
            api.lyrics(server, artist, title)
        } catch (e: SubsonicException) {
            if (e.isAuthError) throw e
            null
        }
        return if (text == null) Lyrics.None else Lyrics.Plain(text)
    }

    /** Without `formPost` the song IDs are in the URL – then send them in blocks. */
    private val chunkSize: Int get() = if (server.supportsFormPost) Int.MAX_VALUE else GET_CHUNK_SIZE

    private suspend fun loadAllAlbums(): Map<String, AlbumDto> {
        val albums = LinkedHashMap<String, AlbumDto>()
        var offset = 0
        while (true) {
            val page = api.albumList(server, "alphabeticalByName", offset, PAGE_SIZE)
            page.forEach { albums[it.id] = it }
            if (page.size < PAGE_SIZE) return albums
            offset += page.size
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val GET_CHUNK_SIZE = 200
        const val NEWEST_PAGE_SIZE = 20
        const val MAX_NEW_ALBUMS = 100
    }
}

@Singleton
class SubsonicDataSourceFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: SubsonicApi,
) {
    fun create(server: SubsonicServer, displayName: String): SubsonicDataSource =
        SubsonicDataSource(server, displayName, api, FallbackNames.from(context))
}
