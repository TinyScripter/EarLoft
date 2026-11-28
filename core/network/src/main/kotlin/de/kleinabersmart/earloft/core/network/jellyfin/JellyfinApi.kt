package de.kleinabersmart.earloft.core.network.jellyfin

import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.UnexpectedResponseException
import de.kleinabersmart.earloft.core.network.await
import de.kleinabersmart.earloft.core.network.notStored
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The response didn't come from a server of the expected kind (wrong URL, different server, error page). */
class NotJellyfinException(message: String, cause: Throwable? = null) : UnexpectedResponseException(message, cause)

/**
 * The Jellyfin and Emby REST calls for music (concept 14); where Emby differs, [JellyfinServer.flavor] decides.
 * Errors come as HTTP status ([HttpStatusException], 401 = token invalid). The token is set by
 * [JellyfinAuthInterceptor] (Emby accepts the same header); URLs only contain it for cast.
 */
@Singleton
class JellyfinApi @Inject constructor(
    private val client: OkHttpClient,
    private val clientInfo: JellyfinClientInfo,
) {
    /** Without login: is there a server of kind [flavor] at [baseUrl]? Jellyfin names itself, Emby gives no name. */
    suspend fun publicInfo(baseUrl: HttpUrl, flavor: JellyfinFlavor = JellyfinFlavor.JELLYFIN): JfPublicSystemInfo {
        val url = baseUrl.newBuilder().addPathSegments("System/Info/Public").build()
        val info = decode<JfPublicSystemInfo>(execute(Request.Builder().url(url).build()), "System/Info/Public")
        val isJellyfin = info.productName?.contains("Jellyfin", ignoreCase = true) == true
        if (info.id == null || isJellyfin != (flavor == JellyfinFlavor.JELLYFIN)) {
            throw NotJellyfinException("Not a ${flavor.productName} server: ${info.productName}")
        }
        return info
    }

    /** Signs in the device [deviceId]; an earlier token of the same device becomes invalid. */
    suspend fun authenticate(baseUrl: HttpUrl, deviceId: String, username: String, password: String): JfAuthResult {
        val url = baseUrl.newBuilder().addPathSegments("Users/AuthenticateByName").build()
        val body = JSON.encodeToString(JfAuthenticate.serializer(), JfAuthenticate(username, password))
        val request = Request.Builder()
            .url(url)
            .header(JellyfinAuthInterceptor.AUTHORIZATION, clientInfo.authorization(deviceId, token = null))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return decode(execute(request, noRedirectClient), "Users/AuthenticateByName")
    }

    /**
     * For the login: the password is in the body, and OkHttp would send it along to any target on 307/308, even from
     * HTTPS to HTTP and to a foreign host (unlike the header with the token). A redirect therefore fails.
     */
    private val noRedirectClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /** Checks the token. Emby doesn't know `Users/Me`. */
    suspend fun me(server: JellyfinServer): JfUser = get(server, server.endpoint(server.choose("Users/Me", "Users/${server.userId}")).build())

    /** Releases the token (when removing the server). */
    suspend fun logout(server: JellyfinServer) {
        execute(request(server, server.endpoint("Sessions/Logout").build()) { post(EMPTY_BODY) })
    }

    /** The account's music libraries; others (movies, audiobooks) are left out. */
    suspend fun musicLibraries(server: JellyfinServer): List<JfItem> {
        val url = when (server.flavor) {
            JellyfinFlavor.JELLYFIN -> server.endpoint("UserViews").addQueryParameter("userId", server.userId).build()
            JellyfinFlavor.EMBY -> server.endpoint("Users/${server.userId}/Views").build()
        }
        return get<JfItemsPage>(server, url).items.filter { it.collectionType == "music" }
    }

    /**
     * One page of songs, oldest first: newly added songs are appended at the end and don't shift the
     * pages of a running sync.
     */
    suspend fun songs(server: JellyfinServer, parentId: String, startIndex: Int, limit: Int): JfItemsPage =
        get(server, songsUrl(server, startIndex, limit) { addQueryParameter("parentId", parentId) }
            .addQueryParameter("sortBy", "DateCreated,SortName")
            .addQueryParameter("sortOrder", "Ascending")
            .build())

    /** Newest songs of library [parentId] first (quick sync). */
    suspend fun newestSongs(server: JellyfinServer, parentId: String, startIndex: Int, limit: Int): JfItemsPage =
        get(server, songsUrl(server, startIndex, limit) { addQueryParameter("parentId", parentId) }
            .addQueryParameter("sortBy", "DateCreated")
            .addQueryParameter("sortOrder", "Descending")
            .build())

    /** All favorited songs (`Audio`), albums (`MusicAlbum`) and artists (`MusicArtist`), across all libraries. */
    suspend fun favoriteMusicItems(server: JellyfinServer): List<JfItem> {
        val url = server.endpoint("Items")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("includeItemTypes", "Audio,MusicAlbum,MusicArtist")
            .addQueryParameter("recursive", "true")
            .addQueryParameter("filters", "IsFavorite")
            .addQueryParameter("enableImages", "false")
            .addQueryParameter("enableUserData", "false")
            .build()
        return get<JfItemsPage>(server, url).items
    }

    /** IDs of up to [limit] songs the server mixes for [itemId] (Instant Mix; Jellyfin and Emby). */
    suspend fun instantMix(server: JellyfinServer, itemId: String, limit: Int): List<String> {
        val url = server.endpoint("Items/$itemId/InstantMix")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("enableImages", "false")
            .addQueryParameter("enableUserData", "false")
            .build()
        return get<JfItemsPage>(server, url).items.map { it.id }
    }

    /** Original file without transcoding (gapless, fidelity); supports `Range`. */
    fun streamUrl(server: JellyfinServer, itemId: String): HttpUrl =
        server.endpoint("Audio/$itemId/stream").addQueryParameter("static", "true").build()

    /** Transcoded by the server; [maxKbps] in kbit/s. The size isn't known in advance. */
    fun transcodedStreamUrl(server: JellyfinServer, itemId: String, codec: String, maxKbps: Int): HttpUrl =
        server.endpoint("Audio/$itemId/stream")
            .addQueryParameter("container", containerFor(codec))
            .addQueryParameter("audioCodec", codec)
            .addQueryParameter("audioBitRate", (maxKbps * 1000).toString())
            .build()

    /** [coverId] from [JellyfinCover.id] or a plain item ID. */
    fun imageUrl(server: JellyfinServer, coverId: String, sizePx: Int): HttpUrl {
        val tag = JellyfinCover.tag(coverId)
        return server.endpoint("Items/${JellyfinCover.itemId(coverId)}/Images/Primary")
            .addQueryParameter("maxWidth", sizePx.toString())
            .addQueryParameter("maxHeight", sizePx.toString())
            .addQueryParameter("quality", "90")
            .apply { if (tag != null) addQueryParameter("tag", tag) }
            .build()
    }

    /** `null` if the entry has no image. */
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
        val url = userItemEndpoint(server, "FavoriteItems", itemId).build()
        execute(request(server, url) { if (favorite) post(EMPTY_BODY) else delete() })
    }

    /**
     * A play like a player does it: start and end (at [durationMs], i.e. fully played). Jellyfin and Emby already count
     * it at the start (`PlayCount`, "played" with the server time); scrobble plugins and the session need the end.
     * Once the start has arrived, the play counts as reported even if the end fails – reported again later, the server
     * would count it twice.
     */
    suspend fun reportPlayback(server: JellyfinServer, itemId: String, durationMs: Long) {
        val session = UUID.randomUUID().toString()
        postJson(server, "Sessions/Playing", JfPlaybackReport(itemId, positionTicks = 0, playSessionId = session))
        try {
            postJson(
                server,
                "Sessions/Playing/Stopped",
                JfPlaybackReport(itemId, positionTicks = durationMs * TICKS_PER_MS, playSessionId = session),
            )
        } catch (e: IOException) {
            // The start already counts; without an end at most the session stays open on the server.
        }
    }

    private suspend fun postJson(server: JellyfinServer, path: String, report: JfPlaybackReport) {
        val body = JSON.encodeToString(JfPlaybackReport.serializer(), report).toRequestBody(JSON_MEDIA_TYPE)
        execute(request(server, server.endpoint(path).build()) { post(body) })
    }

    /** Marks as played, with the timestamp [playedAt] (ms) – also retroactively from the sync queue. */
    suspend fun markPlayed(server: JellyfinServer, itemId: String, playedAt: Long) {
        val playedAtText = when (server.flavor) {
            JellyfinFlavor.JELLYFIN -> Instant.ofEpochMilli(playedAt).toString()
            // Emby doesn't accept ISO-8601, only `yyyyMMddHHmmss` (UTC).
            JellyfinFlavor.EMBY -> EMBY_DATE.format(Instant.ofEpochMilli(playedAt))
        }
        val url = userItemEndpoint(server, "PlayedItems", itemId).addQueryParameter("datePlayed", playedAtText).build()
        execute(request(server, url) { post(EMPTY_BODY) })
    }

    /** All playlists the account sees (own, shared, public). */
    suspend fun playlists(server: JellyfinServer): List<JfItem> {
        val url = server.endpoint("Items")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("includeItemTypes", "Playlist")
            .addQueryParameter("recursive", "true")
            .addQueryParameter("enableImages", "false")
            .addQueryParameter("fields", "Etag")
            .build()
        return get<JfItemsPage>(server, url).items
    }

    /** A single entry (e.g. name of a playlist). */
    suspend fun item(server: JellyfinServer, itemId: String): JfItem = get(server, itemUrl(server, itemId))

    /** The song IDs of a playlist in order (duplicates possible). */
    suspend fun playlistItemIds(server: JellyfinServer, playlistId: String): List<String> = when (server.flavor) {
        JellyfinFlavor.JELLYFIN -> get<JfPlaylist>(server, server.endpoint("Playlists/$playlistId").build()).itemIds
        JellyfinFlavor.EMBY -> embyPlaylistEntries(server, playlistId).map { it.id }
    }

    /**
     * `false` also if the server doesn't know the account at all (404/403). Emby shows each account only its own
     * playlists (checked on server 4.10); it doesn't query their permissions.
     */
    suspend fun canEditPlaylist(server: JellyfinServer, playlistId: String): Boolean {
        if (server.flavor == JellyfinFlavor.EMBY) return true
        return try {
            get<JfPlaylistPermissions>(server, server.endpoint("Playlists/$playlistId/Users/${server.userId}").build()).canEdit
        } catch (e: HttpStatusException) {
            if (e.status == 403 || e.status == 404) false else throw e
        }
    }

    /** Private playlist (not public) with [itemIds]. @return its ID. */
    suspend fun createPlaylist(server: JellyfinServer, name: String, itemIds: List<String>): String {
        if (server.flavor == JellyfinFlavor.EMBY) return createEmbyPlaylist(server, name, itemIds)
        val body = JSON.encodeToString(JfCreatePlaylist.serializer(), JfCreatePlaylist(name, itemIds, server.userId))
            .toRequestBody(JSON_MEDIA_TYPE)
        return decode<JfCreated>(execute(request(server, server.endpoint("Playlists").build()) { post(body) }), "Playlists").id
    }

    /** Replaces name and songs; Jellyfin in one call, Emby step by step ([updateEmbyPlaylist]). */
    suspend fun updatePlaylist(server: JellyfinServer, playlistId: String, name: String, itemIds: List<String>) {
        if (server.flavor == JellyfinFlavor.EMBY) return updateEmbyPlaylist(server, playlistId, name, itemIds)
        val body = JSON.encodeToString(JfUpdatePlaylist.serializer(), JfUpdatePlaylist(name, itemIds)).toRequestBody(JSON_MEDIA_TYPE)
        execute(request(server, server.endpoint("Playlists/$playlistId").build()) { post(body) })
    }

    // --- Emby playlists: creating and changing only via query parameters, entries one by one ---

    private suspend fun embyPlaylistEntries(server: JellyfinServer, playlistId: String): List<JfItem> {
        val url = server.endpoint("Playlists/$playlistId/Items")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("enableImages", "false")
            .addQueryParameter("enableUserData", "false")
            .build()
        return get<JfItemsPage>(server, url).items
    }

    /**
     * Emby doesn't accept a JSON body when creating; long lists go in chunks so the URL stays short. If a
     * chunk fails, the half-created playlist is deleted again – the next attempt creates it anew.
     */
    private suspend fun createEmbyPlaylist(server: JellyfinServer, name: String, itemIds: List<String>): String {
        val chunks = itemIds.chunked(EMBY_IDS_PER_REQUEST)
        val url = server.endpoint("Playlists")
            .addQueryParameter("name", name)
            .addQueryParameter("ids", chunks.firstOrNull().orEmpty().joinToString(","))
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("mediaType", "Audio")
            .build()
        val id = decode<JfCreated>(execute(request(server, url) { post(EMPTY_BODY) }), "Playlists").id
        try {
            chunks.drop(1).forEach { addToEmbyPlaylist(server, id, it) }
        } catch (e: IOException) {
            try {
                deleteItem(server, id)
            } catch (e2: IOException) {
                e.addSuppressed(e2)
            }
            throw e
        }
        return id
    }

    /**
     * Renaming in Emby only works via the whole entry (`POST Items/{id}`, even without admin rights). Of the songs,
     * the common prefix stays; the rest is removed and appended again. Emby renumbers the entries after
     * removal, hence from the back in chunks.
     */
    private suspend fun updateEmbyPlaylist(server: JellyfinServer, playlistId: String, name: String, itemIds: List<String>) {
        val itemUrl = itemUrl(server, playlistId)
        val item = JSON.parseToJsonElement(execute(request(server, itemUrl))).jsonObject
        if ((item["Name"] as? JsonPrimitive)?.contentOrNull != name) {
            val renamed = JsonObject(item + ("Name" to JsonPrimitive(name))).toString().toRequestBody(JSON_MEDIA_TYPE)
            execute(request(server, server.endpoint("Items/$playlistId").build()) { post(renamed) })
        }
        val entries = embyPlaylistEntries(server, playlistId)
        val kept = entries.zip(itemIds).takeWhile { (entry, id) -> entry.id == id }.size
        entries.drop(kept).mapNotNull { it.playlistItemId }.chunked(EMBY_IDS_PER_REQUEST).asReversed().forEach { chunk ->
            val url = server.endpoint("Playlists/$playlistId/Items").addQueryParameter("entryIds", chunk.joinToString(",")).build()
            execute(request(server, url) { delete() })
        }
        itemIds.drop(kept).chunked(EMBY_IDS_PER_REQUEST).forEach { addToEmbyPlaylist(server, playlistId, it) }
    }

    private suspend fun addToEmbyPlaylist(server: JellyfinServer, playlistId: String, itemIds: List<String>) {
        val url = server.endpoint("Playlists/$playlistId/Items")
            .addQueryParameter("ids", itemIds.joinToString(","))
            .addQueryParameter("userId", server.userId)
            .build()
        execute(request(server, url) { post(EMPTY_BODY) })
    }

    suspend fun deleteItem(server: JellyfinServer, itemId: String) {
        execute(request(server, server.endpoint("Items/$itemId").build()) { delete() })
    }

    /** `null` if the song has no lyrics. Jellyfin only; for Emby see [embyLyricsText]. */
    suspend fun lyrics(server: JellyfinServer, itemId: String): JfLyrics? =
        try {
            get(server, server.endpoint("Audio/$itemId/Lyrics").build())
        } catch (e: HttpStatusException) {
            if (e.status == 404) null else throw e
        }

    /**
     * The lyrics on Emby, as LRC or plain text; `null` without lyrics. Emby keeps them as subtitles: an `.lrc`
     * next to the file (synced, gets downloaded) or embedded in the tag (the text is already in the entry).
     */
    suspend fun embyLyricsText(server: JellyfinServer, itemId: String): String? {
        val media = item(server, itemId).mediaSources.firstOrNull() ?: return null
        val streams = media.mediaStreams.filter { it.type == "Subtitle" }
        val external = streams.firstOrNull { it.isExternal && it.codec == "lrc" && it.index != null }
        if (external != null && media.id != null) {
            val url = server.endpoint("Items/$itemId/${media.id}/Subtitles/${external.index}/Stream.lrc").build()
            execute(request(server, url)).removePrefix(BOM).takeIf { it.isNotBlank() }?.let { return it }
        }
        return streams.firstNotNullOfOrNull { stream -> stream.extradata?.removePrefix(BOM)?.takeIf { it.isNotBlank() } }
    }

    /** For receivers outside our OkHttpClient (cast): the token in the URL. Never store or log. */
    fun withToken(server: JellyfinServer, url: HttpUrl): HttpUrl =
        url.newBuilder().addQueryParameter(server.choose("ApiKey", "api_key"), server.token).build()

    /** For Jellyfin `Items/{id}`, for Emby `Users/{userId}/Items/{id}` (Jellyfin 12 no longer knows this path). */
    private fun itemUrl(server: JellyfinServer, itemId: String): HttpUrl = when (server.flavor) {
        JellyfinFlavor.JELLYFIN -> server.endpoint("Items/$itemId").addQueryParameter("userId", server.userId).build()
        JellyfinFlavor.EMBY -> server.endpoint("Users/${server.userId}/Items/$itemId").build()
    }

    /** User data of an entry: Jellyfin 10.9+ `UserFavoriteItems/{id}?userId=`, Emby `Users/{userId}/FavoriteItems/{id}`. */
    private fun userItemEndpoint(server: JellyfinServer, kind: String, itemId: String): HttpUrl.Builder = when (server.flavor) {
        JellyfinFlavor.JELLYFIN -> server.endpoint("User$kind/$itemId").addQueryParameter("userId", server.userId)
        JellyfinFlavor.EMBY -> server.endpoint("Users/${server.userId}/$kind/$itemId")
    }

    private fun JellyfinServer.choose(jellyfin: String, emby: String): String = when (flavor) {
        JellyfinFlavor.JELLYFIN -> jellyfin
        JellyfinFlavor.EMBY -> emby
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

    private suspend fun execute(request: Request, using: OkHttpClient = client): String = withContext(Dispatchers.IO) {
        using.newCall(request.notStored()).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            response.body.string()
        }
    }

    private inline fun <reified T> decode(text: String, endpoint: String): T = decode(serializer(), text, endpoint)

    private fun <T> decode(serializer: KSerializer<T>, text: String, endpoint: String): T =
        try {
            JSON.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw NotJellyfinException("No Jellyfin response from $endpoint", e)
        } catch (e: IllegalArgumentException) {
            throw NotJellyfinException("No Jellyfin response from $endpoint", e)
        }

    internal companion object {
        /** `MediaSources` provides size and bitrate; without the field they are missing. */
        const val SONG_FIELDS = "ProviderIds,DateCreated,Genres,MediaSources"
        const val TICKS_PER_MS = 10_000L

        /** IDs per request when changing Emby playlists (query parameters). */
        const val EMBY_IDS_PER_REQUEST = 200
        private val BOM = Char(0xFEFF).toString()
        private val EMBY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            encodeDefaults = true
        }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        /** Container in which the server delivers the codec. */
        fun containerFor(codec: String): String = when (codec.lowercase()) {
            "opus", "vorbis" -> "ogg"
            "aac" -> "m4a"
            else -> codec
        }
    }
}

/**
 * Cover IDs for Jellyfin: item ID and image tag. The tag changes with the image; if it's part of the ID, the URL
 * changes with the cover too, and the long HTTP cache for covers (`CoverArtCacheInterceptor`) doesn't deliver an old image.
 */
object JellyfinCover {
    private const val SEPARATOR = ':'

    fun id(itemId: String, tag: String?): String = if (tag.isNullOrEmpty()) itemId else "$itemId$SEPARATOR$tag"

    fun itemId(coverId: String): String = coverId.substringBefore(SEPARATOR)

    fun tag(coverId: String): String? = coverId.substringAfter(SEPARATOR, "").ifEmpty { null }
}
