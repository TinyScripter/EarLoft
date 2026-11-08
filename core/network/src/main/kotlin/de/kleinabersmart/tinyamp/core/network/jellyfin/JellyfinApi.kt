package de.kleinabersmart.tinyamp.core.network.jellyfin

import de.kleinabersmart.tinyamp.core.network.HttpStatusException
import de.kleinabersmart.tinyamp.core.network.UnexpectedResponseException
import de.kleinabersmart.tinyamp.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Die Antwort kam nicht von einem Jellyfin-Server (falsche Adresse, anderer Server, Fehlerseite). */
class NotJellyfinException(message: String, cause: Throwable? = null) : UnexpectedResponseException(message, cause)

/**
 * Die Jellyfin-REST-Aufrufe für Musik (Konzept 14). Fehler kommen als HTTP-Status ([HttpStatusException],
 * 401 = Token ungültig). Den Token setzt [JellyfinAuthInterceptor]; URLs enthalten ihn nur für Cast.
 */
@Singleton
class JellyfinApi @Inject constructor(
    private val client: OkHttpClient,
    private val clientInfo: JellyfinClientInfo,
) {
    /** Ohne Anmeldung: Ist unter [baseUrl] ein Jellyfin? */
    suspend fun publicInfo(baseUrl: HttpUrl): JfPublicSystemInfo {
        val url = baseUrl.newBuilder().addPathSegments("System/Info/Public").build()
        val info = decode<JfPublicSystemInfo>(execute(Request.Builder().url(url).build()), "System/Info/Public")
        if (info.id == null || info.productName?.contains("Jellyfin", ignoreCase = true) == false) {
            throw NotJellyfinException("Kein Jellyfin-Server: ${info.productName}")
        }
        return info
    }

    /** Meldet das Gerät [deviceId] an; ein früherer Token desselben Geräts wird dabei ungültig. */
    suspend fun authenticate(baseUrl: HttpUrl, deviceId: String, username: String, password: String): JfAuthResult {
        val url = baseUrl.newBuilder().addPathSegments("Users/AuthenticateByName").build()
        val body = JSON.encodeToString(JfAuthenticate.serializer(), JfAuthenticate(username, password))
        val request = Request.Builder()
            .url(url)
            .header(JellyfinAuthInterceptor.AUTHORIZATION, clientInfo.authorization(deviceId, token = null))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return decode(execute(request), "Users/AuthenticateByName")
    }

    /** Prüft den Token. */
    suspend fun me(server: JellyfinServer): JfUser = get(server, server.endpoint("Users/Me").build())

    /** Gibt den Token frei (beim Entfernen des Servers). */
    suspend fun logout(server: JellyfinServer) {
        execute(request(server, server.endpoint("Sessions/Logout").build()) { post(EMPTY_BODY) })
    }

    /** Die Musik-Bibliotheken des Kontos; andere (Filme, Hörbücher) bleiben außen vor. */
    suspend fun musicLibraries(server: JellyfinServer): List<JfItem> {
        val url = server.endpoint("UserViews").addQueryParameter("userId", server.userId).build()
        return get<JfItemsPage>(server, url).items.filter { it.collectionType == "music" }
    }

    /**
     * Eine Seite Songs, älteste zuerst: Neu hinzukommende Songs hängen hinten an und verschieben die
     * Seiten eines laufenden Abgleichs nicht.
     */
    suspend fun songs(server: JellyfinServer, parentId: String, startIndex: Int, limit: Int): JfItemsPage =
        get(server, songsUrl(server, startIndex, limit) { addQueryParameter("parentId", parentId) }
            .addQueryParameter("sortBy", "DateCreated,SortName")
            .addQueryParameter("sortOrder", "Ascending")
            .build())

    /** Neueste Songs der Bibliothek [parentId] zuerst (Zwischenabgleich). */
    suspend fun newestSongs(server: JellyfinServer, parentId: String, startIndex: Int, limit: Int): JfItemsPage =
        get(server, songsUrl(server, startIndex, limit) { addQueryParameter("parentId", parentId) }
            .addQueryParameter("sortBy", "DateCreated")
            .addQueryParameter("sortOrder", "Descending")
            .build())

    /** IDs aller favorisierten Songs (über alle Bibliotheken). */
    suspend fun favoriteSongIds(server: JellyfinServer): List<String> {
        val url = server.endpoint("Items")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("includeItemTypes", "Audio")
            .addQueryParameter("recursive", "true")
            .addQueryParameter("filters", "IsFavorite")
            .addQueryParameter("enableImages", "false")
            .addQueryParameter("enableUserData", "false")
            .build()
        return get<JfItemsPage>(server, url).items.map { it.id }
    }

    /** Original-Datei ohne Transcoding (Gapless, Klangtreue); kann `Range`. */
    fun streamUrl(server: JellyfinServer, itemId: String): HttpUrl =
        server.endpoint("Audio/$itemId/stream").addQueryParameter("static", "true").build()

    /** Vom Server umgewandelt; [maxKbps] in kbit/s. Die Größe steht vorher nicht fest. */
    fun transcodedStreamUrl(server: JellyfinServer, itemId: String, codec: String, maxKbps: Int): HttpUrl =
        server.endpoint("Audio/$itemId/stream")
            .addQueryParameter("container", containerFor(codec))
            .addQueryParameter("audioCodec", codec)
            .addQueryParameter("audioBitRate", (maxKbps * 1000).toString())
            .build()

    /** [coverId] aus [JellyfinCover.id] oder eine reine Item-ID. */
    fun imageUrl(server: JellyfinServer, coverId: String, sizePx: Int): HttpUrl {
        val tag = JellyfinCover.tag(coverId)
        return server.endpoint("Items/${JellyfinCover.itemId(coverId)}/Images/Primary")
            .addQueryParameter("maxWidth", sizePx.toString())
            .addQueryParameter("maxHeight", sizePx.toString())
            .addQueryParameter("quality", "90")
            .apply { if (tag != null) addQueryParameter("tag", tag) }
            .build()
    }

    /** `null`, wenn der Eintrag kein Bild hat. */
    suspend fun image(server: JellyfinServer, coverId: String, sizePx: Int): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(request(server, imageUrl(server, coverId, sizePx))).await().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw HttpStatusException(response.code)
                response.header("Content-Type")?.startsWith("image/") != true -> null
                else -> response.body.bytes()
            }
        }
    }

    suspend fun setFavorite(server: JellyfinServer, itemId: String, favorite: Boolean) {
        val url = server.endpoint("UserFavoriteItems/$itemId").addQueryParameter("userId", server.userId).build()
        execute(request(server, url) { if (favorite) post(EMPTY_BODY) else delete() })
    }

    /**
     * Eine Wiedergabe wie ein Player: Start und Ende (bei [durationMs], also zu Ende gehört). Jellyfin zählt sie schon
     * beim Start (`PlayCount`, „gespielt“ mit der Serverzeit); das Ende brauchen Scrobble-Plugins und die Sitzung.
     */
    suspend fun reportPlayback(server: JellyfinServer, itemId: String, durationMs: Long) {
        postJson(server, "Sessions/Playing", JfPlaybackReport(itemId, positionTicks = 0))
        postJson(server, "Sessions/Playing/Stopped", JfPlaybackReport(itemId, positionTicks = durationMs * TICKS_PER_MS))
    }

    private suspend fun postJson(server: JellyfinServer, path: String, report: JfPlaybackReport) {
        val body = JSON.encodeToString(JfPlaybackReport.serializer(), report).toRequestBody(JSON_MEDIA_TYPE)
        execute(request(server, server.endpoint(path).build()) { post(body) })
    }

    /** Markiert als gehört, mit dem Zeitpunkt [playedAt] (ms) – auch nachträglich aus der Sync-Queue. */
    suspend fun markPlayed(server: JellyfinServer, itemId: String, playedAt: Long) {
        val url = server.endpoint("UserPlayedItems/$itemId")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("datePlayed", Instant.ofEpochMilli(playedAt).toString())
            .build()
        execute(request(server, url) { post(EMPTY_BODY) })
    }

    /** Alle Playlists, die das Konto sieht (eigene, geteilte, öffentliche). */
    suspend fun playlists(server: JellyfinServer): List<JfItem> {
        val url = server.endpoint("Items")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("includeItemTypes", "Playlist")
            .addQueryParameter("recursive", "true")
            .addQueryParameter("enableImages", "false")
            .build()
        return get<JfItemsPage>(server, url).items
    }

    /** Ein einzelner Eintrag (z. B. Name einer Playlist). */
    suspend fun item(server: JellyfinServer, itemId: String): JfItem =
        get(server, server.endpoint("Items/$itemId").addQueryParameter("userId", server.userId).build())

    suspend fun playlist(server: JellyfinServer, playlistId: String): JfPlaylist =
        get(server, server.endpoint("Playlists/$playlistId").build())

    /** `false` auch, wenn der Server das Konto gar nicht kennt (404/403). */
    suspend fun canEditPlaylist(server: JellyfinServer, playlistId: String): Boolean =
        try {
            get<JfPlaylistPermissions>(server, server.endpoint("Playlists/$playlistId/Users/${server.userId}").build()).canEdit
        } catch (e: HttpStatusException) {
            if (e.status == 403 || e.status == 404) false else throw e
        }

    /** Private Playlist (nicht öffentlich) mit [itemIds]. @return ihre ID. */
    suspend fun createPlaylist(server: JellyfinServer, name: String, itemIds: List<String>): String {
        val body = JSON.encodeToString(JfCreatePlaylist.serializer(), JfCreatePlaylist(name, itemIds, server.userId))
            .toRequestBody(JSON_MEDIA_TYPE)
        return decode<JfCreated>(execute(request(server, server.endpoint("Playlists").build()) { post(body) }), "Playlists").id
    }

    suspend fun updatePlaylist(server: JellyfinServer, playlistId: String, name: String, itemIds: List<String>) {
        val body = JSON.encodeToString(JfUpdatePlaylist.serializer(), JfUpdatePlaylist(name, itemIds)).toRequestBody(JSON_MEDIA_TYPE)
        execute(request(server, server.endpoint("Playlists/$playlistId").build()) { post(body) })
    }

    suspend fun deleteItem(server: JellyfinServer, itemId: String) {
        execute(request(server, server.endpoint("Items/$itemId").build()) { delete() })
    }

    /** `null`, wenn der Song keinen Text hat. */
    suspend fun lyrics(server: JellyfinServer, itemId: String): JfLyrics? =
        try {
            get(server, server.endpoint("Audio/$itemId/Lyrics").build())
        } catch (e: HttpStatusException) {
            if (e.status == 404) null else throw e
        }

    private fun songsUrl(
        server: JellyfinServer,
        startIndex: Int,
        limit: Int,
        configure: HttpUrl.Builder.() -> Unit = {},
    ): HttpUrl.Builder = server.endpoint("Items")
        .addQueryParameter("userId", server.userId)
        .addQueryParameter("includeItemTypes", "Audio")
        .addQueryParameter("recursive", "true")
        .addQueryParameter("fields", SONG_FIELDS)
        .addQueryParameter("enableImageTypes", "Primary")
        .addQueryParameter("imageTypeLimit", "1")
        .addQueryParameter("startIndex", startIndex.toString())
        .addQueryParameter("limit", limit.toString())
        .apply(configure)

    private suspend inline fun <reified T> get(server: JellyfinServer, url: HttpUrl): T =
        decode(execute(request(server, url)), url.encodedPath)

    private fun request(
        server: JellyfinServer,
        url: HttpUrl,
        configure: Request.Builder.() -> Request.Builder = { this },
    ): Request = Request.Builder().url(url).tag(JellyfinServer::class.java, server).configure().build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            response.body.string()
        }
    }

    private inline fun <reified T> decode(text: String, endpoint: String): T = decode(serializer(), text, endpoint)

    private fun <T> decode(serializer: KSerializer<T>, text: String, endpoint: String): T =
        try {
            JSON.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw NotJellyfinException("Keine Jellyfin-Antwort von $endpoint", e)
        } catch (e: IllegalArgumentException) {
            throw NotJellyfinException("Keine Jellyfin-Antwort von $endpoint", e)
        }

    internal companion object {
        /** `MediaSources` liefert Größe und Bitrate; ohne Feld fehlen sie. */
        const val SONG_FIELDS = "ProviderIds,DateCreated,Genres,MediaSources"
        const val TICKS_PER_MS = 10_000L

        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        /** Behälter, in dem der Server den Codec ausliefert. */
        fun containerFor(codec: String): String = when (codec.lowercase()) {
            "opus", "vorbis" -> "ogg"
            "aac" -> "m4a"
            else -> codec
        }
    }
}

/**
 * Cover-IDs für Jellyfin: Item-ID und Bild-Tag. Der Tag ändert sich mit dem Bild; steckt er in der ID, ändert sich mit
 * dem Cover auch dessen URL, und der lange HTTP-Cache für Cover (`CoverArtCacheInterceptor`) liefert kein altes Bild.
 */
object JellyfinCover {
    private const val SEPARATOR = ':'

    fun id(itemId: String, tag: String?): String = if (tag.isNullOrEmpty()) itemId else "$itemId$SEPARATOR$tag"

    fun itemId(coverId: String): String = coverId.substringBefore(SEPARATOR)

    fun tag(coverId: String): String? = coverId.substringAfter(SEPARATOR, "").ifEmpty { null }
}
