package keinabersmart.kleinaberlaut.core.datasource.subsonic

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.datasource.ExternalPlayback
import keinabersmart.kleinaberlaut.core.datasource.MusicDataSource
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.datasource.RemoteLibraryActions
import keinabersmart.kleinaberlaut.core.datasource.RemoteLyrics
import keinabersmart.kleinaberlaut.core.datasource.RawTrack
import keinabersmart.kleinaberlaut.core.datasource.RemotePlaylist
import keinabersmart.kleinaberlaut.core.datasource.RemotePlaylistInfo
import keinabersmart.kleinaberlaut.core.datasource.RemoteQuickSync
import keinabersmart.kleinaberlaut.core.datasource.SourceBatch
import keinabersmart.kleinaberlaut.core.datasource.decodeSampled
import keinabersmart.kleinaberlaut.core.datasource.local.FallbackNames
import keinabersmart.kleinaberlaut.core.datasource.subsonic.SubsonicTrackMapper.toEpochMillis
import keinabersmart.kleinaberlaut.core.model.Lyrics
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicApi
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicCredentials
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicException
import keinabersmart.kleinaberlaut.core.network.subsonic.SubsonicServer
import keinabersmart.kleinaberlaut.core.network.subsonic.authenticatedUrl
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.AlbumDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Ein Navidrome- bzw. Subsonic-Server (Konzept 6.3). */
class SubsonicDataSource internal constructor(
    val server: SubsonicServer,
    override val displayName: String,
    private val api: SubsonicApi,
    private val names: FallbackNames,
) : MusicDataSource, RemoteLibraryActions, RemoteLyrics, RemoteQuickSync, ExternalPlayback {

    override val sourceId: String get() = server.sourceId
    override val type: SourceType = SourceType.SUBSONIC

    /**
     * Album-Metadaten (MBID, Sampler-Kennzeichen, Cover) kommen aus `getAlbumList2`, die Songs aus
     * `search3` mit leerer Suche. Server, die darauf keine Songs liefern, werden Album für Album
     * abgefragt. Fehler brechen den Scan ab, damit der Abgleich nichts löscht.
     */
    override fun fullScan(): Flow<SourceBatch> = flow {
        val albums = loadAllAlbums()
        var offset = 0
        var received = 0
        while (true) {
            val page = api.searchAllSongs(server, offset, PAGE_SIZE)
            received += page.size
            val tracks = page.filter(SubsonicTrackMapper::isMusic)
                .map { SubsonicTrackMapper.map(it, albums[it.albumId], names) }
            if (tracks.isNotEmpty()) emit(SourceBatch(tracks))
            if (page.size < PAGE_SIZE) break
            offset += page.size
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

    /** `getAlbumList2?type=newest` ist nach Anlage-Zeitpunkt absteigend sortiert: Bei einem älteren Album ist Schluss. */
    override suspend fun tracksAddedSince(since: Long): List<RawTrack> {
        val tracks = mutableListOf<RawTrack>()
        var offset = 0
        while (offset < MAX_NEW_ALBUMS) {
            val page = api.albumList(server, "newest", offset, NEWEST_PAGE_SIZE)
            // Ohne Zeitstempel lieber laden (der Merge erkennt Unverändertes).
            val fresh = page.takeWhile { album -> album.created?.toEpochMillis()?.let { it > since } ?: true }
            fresh.forEach { summary ->
                val album = api.album(server, summary.id)
                album.song.filter(SubsonicTrackMapper::isMusic).mapTo(tracks) { SubsonicTrackMapper.map(it, album, names) }
            }
            if (fresh.size < page.size || page.size < NEWEST_PAGE_SIZE) break
            offset += page.size
        }
        return tracks
    }

    override suspend fun starredSongs(): Map<String, Long> =
        api.starredSongs(server).associate { it.id to (it.starred?.toEpochMillis() ?: 0L) }

    override suspend fun resolvePlayback(remoteId: String): PlaybackLocation =
        PlaybackLocation.Remote(
            uri = api.streamUrl(server, remoteId).toString().toUri(),
            cacheKey = "$sourceId:$remoteId",
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

    override suspend fun setFavorite(remoteId: String, favorite: Boolean) {
        if (favorite) api.star(server, remoteId) else api.unstar(server, remoteId)
    }

    override suspend fun scrobble(remoteId: String, playedAt: Long) = api.scrobble(server, remoteId, playedAt)

    /** Fremde (öffentliche) und Smart-Playlists lassen sich nicht ändern und werden ausgelassen. */
    override suspend fun playlists(): List<RemotePlaylistInfo> {
        val username = (server.credentials as? SubsonicCredentials.Password)?.username
        return api.playlists(server)
            .filter { !it.readonly && (username == null || it.owner == null || it.owner == username) }
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
        // Absteigend entfernen: Bei mehreren Aufrufen bleiben die restlichen Indizes gültig.
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

    /** OpenSubsonic zuerst, dann der klassische `getLyrics`-Aufruf (nur Text). */
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

    /** Ohne `formPost` stehen die Song-IDs in der URL – dann in Blöcken senden. */
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
