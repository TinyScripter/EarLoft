package de.kleinabersmart.tinyamp.core.datasource.jellyfin

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.datasource.ExternalPlayback
import de.kleinabersmart.tinyamp.core.datasource.MusicDataSource
import de.kleinabersmart.tinyamp.core.datasource.PlaybackLocation
import de.kleinabersmart.tinyamp.core.datasource.RawTrack
import de.kleinabersmart.tinyamp.core.datasource.RemoteLibraryActions
import de.kleinabersmart.tinyamp.core.datasource.RemoteLyrics
import de.kleinabersmart.tinyamp.core.datasource.RemotePlaylist
import de.kleinabersmart.tinyamp.core.datasource.RemotePlaylistInfo
import de.kleinabersmart.tinyamp.core.datasource.RemoteQuickSync
import de.kleinabersmart.tinyamp.core.datasource.RemoteTranscoding
import de.kleinabersmart.tinyamp.core.datasource.SourceBatch
import de.kleinabersmart.tinyamp.core.datasource.decodeSampled
import de.kleinabersmart.tinyamp.core.datasource.jellyfin.JellyfinTrackMapper.toEpochMillis
import de.kleinabersmart.tinyamp.core.datasource.local.FallbackNames
import de.kleinabersmart.tinyamp.core.model.Lyrics
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.network.jellyfin.JellyfinApi
import de.kleinabersmart.tinyamp.core.network.jellyfin.JellyfinServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/** Ein Jellyfin-Server (Konzept 14); nur seine Musik-Bibliotheken. */
class JellyfinDataSource internal constructor(
    val server: JellyfinServer,
    override val displayName: String,
    private val api: JellyfinApi,
    private val names: FallbackNames,
) : MusicDataSource, RemoteLibraryActions, RemoteLyrics, RemoteQuickSync, ExternalPlayback, RemoteTranscoding {

    override val sourceId: String get() = server.sourceId
    override val type: SourceType = SourceType.JELLYFIN

    /** Bibliothek für Bibliothek in Seiten; Fehler brechen den Scan ab, damit der Abgleich nichts löscht. */
    override fun fullScan(): Flow<SourceBatch> = flow {
        api.musicLibraries(server).forEach { library ->
            var start = 0
            while (true) {
                val page = api.songs(server, library.id, start, PAGE_SIZE).items
                val tracks = page.filter(JellyfinTrackMapper::isMusic).map { JellyfinTrackMapper.map(it, names) }
                if (tracks.isNotEmpty()) emit(SourceBatch(tracks))
                if (page.size < PAGE_SIZE) break
                start += page.size
            }
        }
    }

    /**
     * Neueste zuerst, wie der Vollabgleich nur aus den Musik-Bibliotheken (sonst löschte der nächste Vollabgleich wieder,
     * was hier aus anderen Bibliotheken käme). Je Bibliothek ist beim ersten älteren Song Schluss.
     */
    override suspend fun tracksAddedSince(since: Long): List<RawTrack> {
        val tracks = mutableListOf<RawTrack>()
        api.musicLibraries(server).forEach { library ->
            var start = 0
            while (start < MAX_NEW_SONGS) {
                val page = api.newestSongs(server, library.id, start, NEWEST_PAGE_SIZE).items
                // Ohne Zeitstempel lieber laden (der Merge erkennt Unverändertes).
                val fresh = page.takeWhile { item -> item.dateCreated?.toEpochMillis()?.let { it > since } ?: true }
                fresh.filter(JellyfinTrackMapper::isMusic).mapTo(tracks) { JellyfinTrackMapper.map(it, names) }
                if (fresh.size < page.size || page.size < NEWEST_PAGE_SIZE) break
                start += page.size
            }
        }
        return tracks
    }

    /** Jellyfin merkt sich nicht, seit wann ein Song Favorit ist. */
    override suspend fun starredSongs(): Map<String, Long> = api.favoriteSongIds(server).associateWith { 0L }

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
        return url.withApiKey().toString()
    }

    override fun externalCoverUrl(coverRemoteId: String, sizePx: Int): String =
        api.imageUrl(server, coverRemoteId, sizePx).withApiKey().toString()

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? {
        val bytes = api.image(server, remoteId, sizePx) ?: return null
        return withContext(Dispatchers.Default) { decodeSampled(bytes, sizePx) }
    }

    override suspend fun setFavorite(remoteId: String, favorite: Boolean) = api.setFavorite(server, remoteId, favorite)

    /** „Gehört“ mit Zeitpunkt; Last.fm und ListenBrainz erreichen nur Plugins des Servers (Konzept 14). */
    override suspend fun scrobble(remoteId: String, playedAt: Long) = api.markPlayed(server, remoteId, playedAt)

    /** Nur Musik-Playlists, die das Konto ändern darf; je Playlist zwei Anfragen (Rechte, Einträge für den Hash). */
    override suspend fun playlists(): List<RemotePlaylistInfo> =
        api.playlists(server)
            .filter { it.mediaType == null || it.mediaType == "Audio" }
            .filter { api.canEditPlaylist(server, it.id) }
            .map { JellyfinTrackMapper.playlistInfo(it.id, it.name.orEmpty(), api.playlist(server, it.id).itemIds) }

    override suspend fun playlist(remoteId: String): RemotePlaylist {
        val ids = api.playlist(server, remoteId).itemIds
        val name = api.item(server, remoteId).name.orEmpty()
        return RemotePlaylist(JellyfinTrackMapper.playlistInfo(remoteId, name, ids), ids)
    }

    override suspend fun createPlaylist(name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        val remoteId = api.createPlaylist(server, name, songRemoteIds)
        return playlist(remoteId).info
    }

    /** Jellyfin ersetzt Name und Einträge in einem Aufruf. */
    override suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        api.updatePlaylist(server, current.info.remoteId, name, songRemoteIds)
        return playlist(current.info.remoteId).info
    }

    override suspend fun deletePlaylist(remoteId: String) = api.deleteItem(server, remoteId)

    override suspend fun lyrics(remoteId: String, artist: String, title: String): Lyrics =
        api.lyrics(server, remoteId)?.let(JellyfinTrackMapper::lyrics) ?: Lyrics.None

    /** Nur für Empfänger außerhalb unseres OkHttpClients (Cast): nie speichern oder loggen. */
    private fun HttpUrl.withApiKey(): HttpUrl = newBuilder().addQueryParameter("ApiKey", server.token).build()

    private companion object {
        const val PAGE_SIZE = 500
        const val NEWEST_PAGE_SIZE = 100
        const val MAX_NEW_SONGS = 1000
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
