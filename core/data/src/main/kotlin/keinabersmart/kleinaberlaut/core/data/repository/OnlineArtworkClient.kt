package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.common.normalizeForMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bilder aus dem Internet:
 * - Interpreten: Deezer (Suche per Name, ohne Schlüssel),
 * - Album-Cover: MusicBrainz (Release suchen) und das Cover Art Archive (Bild),
 * - Autoren (Bücher): Audnexus (Suche per Name, ohne Schlüssel).
 *
 * `null` heißt „es gibt dort nichts“; Netzwerkfehler werden als [IOException] geworfen und dürfen nicht
 * als „nicht gefunden“ gemerkt werden.
 */
@Singleton
class OnlineArtworkClient @Inject constructor(
    private val client: OkHttpClient,
) {
    private val musicBrainzLock = Mutex()
    private var lastMusicBrainzCall = 0L

    suspend fun findArtistImage(name: String): ByteArray? {
        val search = DEEZER_SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("q", name)
            .addQueryParameter("limit", "5")
            .build()
        val results = getJson(search.toString()) ?: return null
        val data = results.optJSONArray("data") ?: return null
        val wanted = name.normalizeForMerge()
        // Nur ein Treffer mit gleichem Namen; sonst bekäme "Air" das Bild einer anderen Band.
        for (i in 0 until data.length()) {
            val artist = data.optJSONObject(i) ?: continue
            if (artist.optString("name").normalizeForMerge() != wanted) continue
            val picture = artist.optString("picture_xl").ifEmpty { artist.optString("picture_big") }
            // Ohne Foto liefert Deezer eine Platzhalter-Adresse ohne Bild-ID ("/artist//").
            if (picture.isEmpty() || "/artist//" in picture) return null
            return getBytes(picture)
        }
        return null
    }

    /**
     * Autorenbild von Audnexus (die Autoren-Daten von Audible, die auch Audiobookshelf nutzt): erst den Autor per Name suchen,
     * dann sein Bild laden. Nur ein Treffer mit gleichem Namen zählt.
     */
    suspend fun findAuthorImage(name: String): ByteArray? {
        val search = "$AUDNEXUS/authors".toHttpUrl().newBuilder().addQueryParameter("name", name).build()
        val results = getBytes(search.toString())?.let { JSONArray(String(it, Charsets.UTF_8)) } ?: return null
        val wanted = name.normalizeForMerge()
        val asin = (0 until results.length())
            .mapNotNull { results.optJSONObject(it) }
            .firstOrNull { it.optString("name").normalizeForMerge() == wanted && it.optString("asin").isNotEmpty() }
            ?.optString("asin") ?: return null
        val image = getJson("$AUDNEXUS/authors/$asin")?.optString("image").orEmpty()
        return if (image.isEmpty()) null else getBytes(image)
    }

    suspend fun findAlbumCover(title: String, artist: String, releaseMbid: String?): ByteArray? {
        if (releaseMbid != null) getBytes("$COVER_ART_ARCHIVE/release/$releaseMbid/front-500")?.let { return it }
        val query = "releasegroup:\"${title.luceneEscaped()}\" AND artist:\"${artist.luceneEscaped()}\""
        val url = "$MUSICBRAINZ/release-group".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", "1")
            .addQueryParameter("fmt", "json")
            .build()
        val groups = musicBrainzJson(url.toString())?.optJSONArray("release-groups") ?: return null
        val group = groups.optJSONObject(0) ?: return null
        // Niedrige Treffer-Werte sind eher ein anderes Album mit ähnlichem Titel.
        if (group.optInt("score") < MIN_SCORE) return null
        val id = group.optString("id").ifEmpty { return null }
        return getBytes("$COVER_ART_ARCHIVE/release-group/$id/front-500")
    }

    /** MusicBrainz erlaubt eine Anfrage pro Sekunde. */
    private suspend fun musicBrainzJson(url: String): JSONObject? = musicBrainzLock.withLock {
        val wait = lastMusicBrainzCall + MUSICBRAINZ_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            getJson(url)
        } finally {
            lastMusicBrainzCall = System.currentTimeMillis()
        }
    }

    private suspend fun getJson(url: String): JSONObject? =
        getBytes(url)?.let { JSONObject(String(it, Charsets.UTF_8)) }

    private suspend fun getBytes(url: String): ByteArray? = runInterruptible(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw IOException("HTTP ${response.code} von $url")
                else -> response.body.bytes()
            }
        }
    }

    private fun String.luceneEscaped() = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val DEEZER_SEARCH = "https://api.deezer.com/search/artist"
        const val MUSICBRAINZ = "https://musicbrainz.org/ws/2"
        const val COVER_ART_ARCHIVE = "https://coverartarchive.org"
        const val AUDNEXUS = "https://api.audnex.us"
        const val USER_AGENT = "kleinaberlaut/1.0 (Android music player)"
        const val MUSICBRAINZ_INTERVAL_MS = 1_100L
        const val MIN_SCORE = 90
    }
}
