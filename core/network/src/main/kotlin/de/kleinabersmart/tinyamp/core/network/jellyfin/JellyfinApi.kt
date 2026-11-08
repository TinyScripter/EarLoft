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

/** Die Antwort kam nicht von einem Server der erwarteten Art (falsche Adresse, anderer Server, Fehlerseite). */
class NotJellyfinException(message: String, cause: Throwable? = null) : UnexpectedResponseException(message, cause)

/**
 * Die Jellyfin- und Emby-REST-Aufrufe für Musik (Konzept 14); wo Emby abweicht, entscheidet [JellyfinServer.flavor].
 * Fehler kommen als HTTP-Status ([HttpStatusException], 401 = Token ungültig). Den Token setzt
 * [JellyfinAuthInterceptor] (Emby nimmt dieselbe Kopfzeile an); URLs enthalten ihn nur für Cast.
 */
@Singleton
class JellyfinApi @Inject constructor(
    private val client: OkHttpClient,
    private val clientInfo: JellyfinClientInfo,
) {
    /** Ohne Anmeldung: Ist unter [baseUrl] ein Server der Art [flavor]? Jellyfin nennt sich, Emby nennt keinen Namen. */
    suspend fun publicInfo(baseUrl: HttpUrl, flavor: JellyfinFlavor = JellyfinFlavor.JELLYFIN): JfPublicSystemInfo {
        val url = baseUrl.newBuilder().addPathSegments("System/Info/Public").build()
        val info = decode<JfPublicSystemInfo>(execute(Request.Builder().url(url).build()), "System/Info/Public")
        val isJellyfin = info.productName?.contains("Jellyfin", ignoreCase = true) == true
        if (info.id == null || isJellyfin != (flavor == JellyfinFlavor.JELLYFIN)) {
            throw NotJellyfinException("Kein ${flavor.productName}-Server: ${info.productName}")
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
        return decode(execute(request, noRedirectClient), "Users/AuthenticateByName")
    }

    /**
     * Für die Anmeldung: Das Passwort steht im Body, und den schickte OkHttp bei 307/308 an jedes Ziel mit, auch von
     * HTTPS auf HTTP und zu einem fremden Host (anders als die Kopfzeile mit dem Token). Eine Weiterleitung scheitert so.
     */
    private val noRedirectClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    /** Prüft den Token. Emby kennt `Users/Me` nicht. */
    suspend fun me(server: JellyfinServer): JfUser = get(server, server.endpoint(server.choose("Users/Me", "Users/${server.userId}")).build())

    /** Gibt den Token frei (beim Entfernen des Servers). */
    suspend fun logout(server: JellyfinServer) {
        execute(request(server, server.endpoint("Sessions/Logout").build()) { post(EMPTY_BODY) })
    }

    /** Die Musik-Bibliotheken des Kontos; andere (Filme, Hörbücher) bleiben außen vor. */
    suspend fun musicLibraries(server: JellyfinServer): List<JfItem> {
        val url = when (server.flavor) {
            JellyfinFlavor.JELLYFIN -> server.endpoint("UserViews").addQueryParameter("userId", server.userId).build()
            JellyfinFlavor.EMBY -> server.endpoint("Users/${server.userId}/Views").build()
        }
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
        val url = userItemEndpoint(server, "FavoriteItems", itemId).build()
        execute(request(server, url) { if (favorite) post(EMPTY_BODY) else delete() })
    }

    /**
     * Eine Wiedergabe wie ein Player: Start und Ende (bei [durationMs], also zu Ende gehört). Jellyfin und Emby zählen
     * sie schon beim Start (`PlayCount`, „gespielt“ mit der Serverzeit); das Ende brauchen Scrobble-Plugins und die Sitzung.
     * Ist der Start angekommen, gilt die Wiedergabe als gemeldet, auch wenn das Ende scheitert – nachgereicht zählte sie
     * der Server doppelt.
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
            // Der Start zählt schon; ohne Ende bleibt am Server höchstens die Sitzung offen.
        }
    }

    private suspend fun postJson(server: JellyfinServer, path: String, report: JfPlaybackReport) {
        val body = JSON.encodeToString(JfPlaybackReport.serializer(), report).toRequestBody(JSON_MEDIA_TYPE)
        execute(request(server, server.endpoint(path).build()) { post(body) })
    }

    /** Markiert als gehört, mit dem Zeitpunkt [playedAt] (ms) – auch nachträglich aus der Sync-Queue. */
    suspend fun markPlayed(server: JellyfinServer, itemId: String, playedAt: Long) {
        val playedAtText = when (server.flavor) {
            JellyfinFlavor.JELLYFIN -> Instant.ofEpochMilli(playedAt).toString()
            // Emby nimmt kein ISO-8601, nur `yyyyMMddHHmmss` (UTC).
            JellyfinFlavor.EMBY -> EMBY_DATE.format(Instant.ofEpochMilli(playedAt))
        }
        val url = userItemEndpoint(server, "PlayedItems", itemId).addQueryParameter("datePlayed", playedAtText).build()
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
    suspend fun item(server: JellyfinServer, itemId: String): JfItem = get(server, itemUrl(server, itemId))

    /** Die Song-IDs einer Playlist in Reihenfolge (Duplikate möglich). */
    suspend fun playlistItemIds(server: JellyfinServer, playlistId: String): List<String> = when (server.flavor) {
        JellyfinFlavor.JELLYFIN -> get<JfPlaylist>(server, server.endpoint("Playlists/$playlistId").build()).itemIds
        JellyfinFlavor.EMBY -> embyPlaylistEntries(server, playlistId).map { it.id }
    }

    /**
     * `false` auch, wenn der Server das Konto gar nicht kennt (404/403). Emby zeigt jedem Konto nur die eigenen
     * Playlists (am Server 4.10 geprüft); deren Rechte fragt es nicht ab.
     */
    suspend fun canEditPlaylist(server: JellyfinServer, playlistId: String): Boolean {
        if (server.flavor == JellyfinFlavor.EMBY) return true
        return try {
            get<JfPlaylistPermissions>(server, server.endpoint("Playlists/$playlistId/Users/${server.userId}").build()).canEdit
        } catch (e: HttpStatusException) {
            if (e.status == 403 || e.status == 404) false else throw e
        }
    }

    /** Private Playlist (nicht öffentlich) mit [itemIds]. @return ihre ID. */
    suspend fun createPlaylist(server: JellyfinServer, name: String, itemIds: List<String>): String {
        if (server.flavor == JellyfinFlavor.EMBY) return createEmbyPlaylist(server, name, itemIds)
        val body = JSON.encodeToString(JfCreatePlaylist.serializer(), JfCreatePlaylist(name, itemIds, server.userId))
            .toRequestBody(JSON_MEDIA_TYPE)
        return decode<JfCreated>(execute(request(server, server.endpoint("Playlists").build()) { post(body) }), "Playlists").id
    }

    /** Ersetzt Name und Songs; Jellyfin in einem Aufruf, Emby Schritt für Schritt ([updateEmbyPlaylist]). */
    suspend fun updatePlaylist(server: JellyfinServer, playlistId: String, name: String, itemIds: List<String>) {
        if (server.flavor == JellyfinFlavor.EMBY) return updateEmbyPlaylist(server, playlistId, name, itemIds)
        val body = JSON.encodeToString(JfUpdatePlaylist.serializer(), JfUpdatePlaylist(name, itemIds)).toRequestBody(JSON_MEDIA_TYPE)
        execute(request(server, server.endpoint("Playlists/$playlistId").build()) { post(body) })
    }

    // --- Emby-Playlists: Anlegen und Ändern nur über Query-Parameter, Einträge einzeln ---

    private suspend fun embyPlaylistEntries(server: JellyfinServer, playlistId: String): List<JfItem> {
        val url = server.endpoint("Playlists/$playlistId/Items")
            .addQueryParameter("userId", server.userId)
            .addQueryParameter("enableImages", "false")
            .addQueryParameter("enableUserData", "false")
            .build()
        return get<JfItemsPage>(server, url).items
    }

    /**
     * Emby nimmt beim Anlegen keinen JSON-Body; lange Listen gehen in Stücken, damit die URL kurz bleibt. Scheitert ein
     * Stück, wird die halbe Playlist wieder gelöscht – der nächste Versuch legt sie neu an.
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
     * Umbenennen geht bei Emby nur über den ganzen Eintrag (`POST Items/{id}`, auch ohne Admin-Rechte). Von den Songs
     * bleibt der gemeinsame Anfang; der Rest wird entfernt und neu angehängt. Emby nummeriert die Einträge nach dem
     * Entfernen neu, deshalb von hinten in Stücken.
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

    /** `null`, wenn der Song keinen Text hat. Nur Jellyfin; Emby siehe [embyLyricsText]. */
    suspend fun lyrics(server: JellyfinServer, itemId: String): JfLyrics? =
        try {
            get(server, server.endpoint("Audio/$itemId/Lyrics").build())
        } catch (e: HttpStatusException) {
            if (e.status == 404) null else throw e
        }

    /**
     * Der Songtext bei Emby, als LRC oder reiner Text; `null` ohne Text. Emby führt ihn als Untertitel: eine `.lrc`
     * neben der Datei (synchron, wird geladen) oder eingebettet im Tag (der Text steht schon im Eintrag).
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

    /** Für Empfänger außerhalb unseres OkHttpClients (Cast): der Token in der URL. Nie speichern oder loggen. */
    fun withToken(server: JellyfinServer, url: HttpUrl): HttpUrl =
        url.newBuilder().addQueryParameter(server.choose("ApiKey", "api_key"), server.token).build()

    /** Bei Jellyfin `Items/{id}`, bei Emby `Users/{userId}/Items/{id}` (Jellyfin 12 kennt diesen Pfad nicht mehr). */
    private fun itemUrl(server: JellyfinServer, itemId: String): HttpUrl = when (server.flavor) {
        JellyfinFlavor.JELLYFIN -> server.endpoint("Items/$itemId").addQueryParameter("userId", server.userId).build()
        JellyfinFlavor.EMBY -> server.endpoint("Users/${server.userId}/Items/$itemId").build()
    }

    /** Nutzerdaten eines Eintrags: Jellyfin ab 10.9 `UserFavoriteItems/{id}?userId=`, Emby `Users/{userId}/FavoriteItems/{id}`. */
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
        using.newCall(request).await().use { response ->
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

        /** IDs je Anfrage beim Ändern von Emby-Playlists (Query-Parameter). */
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
