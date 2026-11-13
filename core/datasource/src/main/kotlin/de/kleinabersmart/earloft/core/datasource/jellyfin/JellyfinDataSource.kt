package de.kleinabersmart.earloft.core.datasource.jellyfin

import android.content.Context
import android.graphics.Bitmap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.datasource.ExternalPlayback
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

/** Ein Jellyfin- oder Emby-Server (Konzept 14); nur seine Musik-Bibliotheken. */
class JellyfinDataSource internal constructor(
    val server: JellyfinServer,
    override val displayName: String,
    private val api: JellyfinApi,
    private val names: FallbackNames,
) : MusicDataSource, RemoteLibraryActions, RemoteLyrics, RemoteQuickSync, ExternalPlayback, RemoteTranscoding,
    RemotePlaybackReporting, RemoteSimilarSongs {

    override val sourceId: String get() = server.sourceId
    override val type: SourceType = server.flavor.sourceType

    /** Playlist → darf dieses Konto sie bearbeiten, und wann gefragt (`nanoTime`). */
    private val editable = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** Playlist → `Etag` und Einträge dazu. */
    private val playlistItems = ConcurrentHashMap<String, CachedItems>()

    private class CachedItems(val etag: String, val ids: List<String>, val fetchedAt: Long)

    /**
     * Bibliothek für Bibliothek in Seiten bis zur gemeldeten Gesamtzahl; Fehler brechen den Scan ab, damit der Abgleich
     * nichts löscht. Kürzt ein Proxy die Seiten, geht es mit der nächsten weiter; bleibt eine Seite vor dem Ende leer,
     * fehlen Songs, und der Scan bricht ab, statt sie lokal zu löschen.
     */
    override fun fullScan(): Flow<SourceBatch> = flow {
        api.musicLibraries(server).forEach { library ->
            // Hat sich die Bibliothek unterwegs verschoben (Scan oder Löschen am Server), einmal von vorn.
            if (!scanLibrary(library) && !scanLibrary(library)) {
                throw IOException("${library.name} hat sich während des Abgleichs geändert")
            }
        }
    }

    /**
     * Ab der zweiten Seite beginnt jede Anfrage einen Song früher: Er muss der letzte der vorigen Seite sein. Sonst ist
     * vor dem Offset etwas weggefallen, und der erste Song der Seite fehlte – der Abgleich löschte ihn samt Playlist-
     * Einträgen, Verlauf und Favorit. @return `false` bei einer Verschiebung.
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
                // Ohne Gesamtzahl wie früher: Eine kurze Seite ist die letzte.
                ?: if (page.size < PAGE_SIZE) return true else continue
            if (start >= total) return true
            if (page.isEmpty()) throw IOException("Jellyfin lieferte nur $start von $total Songs aus ${library.name}")
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

    /** Jellyfin merkt sich nicht, seit wann etwas Favorit ist. */
    override suspend fun starred(): RemoteStarred {
        val byType = api.favoriteMusicItems(server).groupBy({ it.type }, { it.id to 0L })
        return RemoteStarred(
            songs = byType["Audio"].orEmpty().toMap(),
            albums = byType["MusicAlbum"].orEmpty().toMap(),
            artists = byType["MusicArtist"].orEmpty().toMap(),
        )
    }

    /** Unbekanntes (z. B. aus anderen Bibliotheken) verwirft der Mix ohnehin. */
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

    /** Jellyfin und Emby liefern Bilder ohne Anmeldung; der Token bleibt aus der URL für den Empfänger. */
    override fun externalCoverUrl(coverRemoteId: String, sizePx: Int): String =
        api.imageUrl(server, coverRemoteId, sizePx).toString()

    override suspend fun loadArtwork(remoteId: String, sizePx: Int): Bitmap? {
        val bytes = api.image(server, remoteId, sizePx) ?: return null
        return withContext(Dispatchers.Default) { decodeSampled(bytes, sizePx) }
    }

    /** Songs, Alben und Künstler sind Einträge desselben Aufrufs. */
    override suspend fun setFavorite(remoteId: String, favorite: Boolean, kind: FavoriteKind) =
        api.setFavorite(server, remoteId, favorite)

    /**
     * Nachgereicht: „gehört“ mit Zeitpunkt. Scrobble-Plugins des Servers sehen das nur, wenn sie auf geänderte
     * Nutzerdaten hören (ListenBrainz: Modus `UserDataSaved`); live geht es über [reportPlayback] (Konzept 14).
     */
    override suspend fun scrobble(remoteId: String, playedAt: Long) = api.markPlayed(server, remoteId, playedAt)

    override suspend fun reportPlayback(remoteId: String, durationMs: Long) = api.reportPlayback(server, remoteId, durationMs)

    /**
     * Nur Musik-Playlists, die das Konto ändern darf. Bei jedem Abgleich (auch dem schnellen nach dem Start) wären das zwei
     * Anfragen je sichtbarer Playlist, auch je fremder öffentlicher: deshalb das Bearbeitungsrecht [EDIT_CHECK_MS] lang gemerkt
     * und die Einträge nur neu geholt, wenn sich der `Etag` geändert hat. Der Änderungs-Hash bleibt derselbe (Name und Einträge).
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
     * Jellyfin 12.4 ändert den `Etag` einer Playlist mit Name und Einträgen, auch beim Umsortieren. Emby 4.10 ändert ihn beim
     * Hinzufügen, Entfernen und Umbenennen, beim Umsortieren nicht (beides gemessen): Dort gelten die Einträge höchstens
     * [EMBY_ITEMS_MAX_AGE_MS] lang, eine Umsortierung in einem anderen Programm kommt so spätestens danach an.
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
        // Nicht zurücklesen: Scheiterte das, legte der nächste Versuch eine zweite Playlist an. Weicht der Server ab,
        // holt der nächste Abgleich seinen Stand.
        return JellyfinTrackMapper.playlistInfo(remoteId, name, songRemoteIds)
    }

    /** Jellyfin ersetzt Name und Einträge in einem Aufruf, Emby in mehreren. */
    override suspend fun replacePlaylist(current: RemotePlaylist, name: String, songRemoteIds: List<String>): RemotePlaylistInfo {
        awaitSettled(current.info.remoteId)
        // Eine eigene Umsortierung ändert bei Emby den `Etag` nicht: Die gemerkten Einträge sind danach veraltet und gälten
        // beim nächsten Abgleich als Änderung auf dem Server.
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
     * Jellyfin arbeitet eine neue Playlist kurz nach dem Anlegen noch einmal auf und setzt dabei Name und Einträge auf den
     * Stand beim Anlegen zurück (am Testserver mit 12.4: Änderung sofort danach verloren, nach 2 s nicht mehr). Offline
     * Angelegtes und Geändertes fasst die Sync-Queue zu einem Anlegen zusammen; betroffen ist eine Änderung gleich nach
     * dem Anlegen. Emby 4.10 setzt nichts zurück (gemessen), dort wird nicht gewartet.
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

        /** Je Prozess: Die Datenquelle wird beim Neuladen der Server neu erzeugt, die Playlist bleibt frisch. */
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
