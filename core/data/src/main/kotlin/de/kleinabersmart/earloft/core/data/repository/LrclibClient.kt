package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.model.LrcParser
import de.kleinabersmart.earloft.core.model.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Songtexte von LRCLIB (lrclib.net, ohne Schlüssel). Zuerst wird ein Eintrag mit Titel, Interpret und Dauer geholt;
 * LRCLIB nimmt dafür nur Aufnahmen, deren Dauer höchstens 2 s abweicht, damit die Zeiten synchroner Zeilen passen.
 * Gibt es keinen, wird gesucht und nur ein statischer Text übernommen: Eine andere Fassung hat andere Zeiten.
 *
 * [Lyrics.None] heißt „es gibt dort nichts“; Netzwerkfehler werden als [IOException] geworfen.
 */
@Singleton
class LrclibClient internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    @Inject
    constructor(client: OkHttpClient) : this(client, BASE_URL)

    /** [artists] in der Reihenfolge, in der gefragt wird (etwa erst „A feat. B“, dann „A“). */
    suspend fun find(title: String, artists: List<String>, durationMs: Long): Lyrics {
        val names = artists.filter { it.isNotBlank() }.distinct()
        if (title.isBlank() || names.isEmpty()) return Lyrics.None
        val seconds = (durationMs + 500) / 1_000
        if (seconds in 1..MAX_DURATION_S) {
            for (artist in names) {
                val url = api("get")
                    .addQueryParameter("track_name", title)
                    .addQueryParameter("artist_name", artist)
                    .addQueryParameter("duration", seconds.toString())
                    .build()
                val found = get(url)?.let { parsed { lyricsOf(JSONObject(it)) } } ?: continue
                if (found != Lyrics.None) return found
            }
        }
        return searchPlain(title, names)
    }

    private suspend fun searchPlain(title: String, artists: List<String>): Lyrics {
        val url = api("search")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artists.first())
            .build()
        val results = get(url)?.let { parsed { JSONArray(it) } } ?: return Lyrics.None
        val wantedTitle = title.normalizeForMerge()
        val wantedArtists = artists.map { it.normalizeForMerge() }.toSet()
        // Die Suche ist unscharf; nur gleicher Titel und Interpret zählen, sonst käme der Text eines anderen Songs.
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            if (entry.optBoolean("instrumental")) continue
            if (entry.optString("trackName").normalizeForMerge() != wantedTitle) continue
            if (entry.optString("artistName").normalizeForMerge() !in wantedArtists) continue
            val plain = entry.text("plainLyrics")
            if (plain.isNotEmpty()) return Lyrics.Plain(plain)
        }
        return Lyrics.None
    }

    private fun lyricsOf(entry: JSONObject): Lyrics {
        if (entry.optBoolean("instrumental")) return Lyrics.None
        val synced = LrcParser.parse(entry.text("syncedLyrics"))
        if (synced is Lyrics.Synced) return synced
        val plain = entry.text("plainLyrics")
        return if (plain.isEmpty()) Lyrics.None else Lyrics.Plain(plain)
    }

    /** `optString` lieferte für JSON-`null` den Text "null". */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name).trim()

    private fun api(path: String): HttpUrl.Builder = "$baseUrl/api/$path".toHttpUrl().newBuilder()

    /**
     * Antwort als Text; `null` bei 404 (nichts gefunden). LRCLIB begrenzt die Anfragen je IP-Adresse und nennt bei 429
     * die Wartezeit (meist 1 s); ist sie kurz, wird einmal gewartet und neu gefragt.
     */
    private suspend fun get(url: HttpUrl, retry: Boolean = true): String? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val (code, body, retryAfterS) = runInterruptible(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                Triple(
                    response.code,
                    if (response.isSuccessful) response.body.string() else null,
                    response.header("Retry-After")?.toLongOrNull(),
                )
            }
        }
        return when {
            code == 404 -> null
            code == 429 && retry && (retryAfterS ?: 1) <= MAX_RETRY_AFTER_S -> {
                delay((retryAfterS ?: 1) * 1_000)
                get(url, retry = false)
            }
            body == null -> throw IOException("HTTP $code von LRCLIB")
            else -> body
        }
    }

    /** Kaputte Antworten zählen wie ein Netzwerkfehler: nicht als „kein Text“ merken. */
    private inline fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("Unlesbare Antwort von LRCLIB", e)
    }

    private companion object {
        const val BASE_URL = "https://lrclib.net"
        const val USER_AGENT = "EarLoft/1.0 (Android music player)"

        /** LRCLIB lehnt bei `get` Dauern außerhalb von 1 bis 3600 s ab. */
        const val MAX_DURATION_S = 3_600L

        /** Längere Wartezeiten nach 429 gelten als „nicht erreichbar“; der Nutzer kann es später erneut versuchen. */
        const val MAX_RETRY_AFTER_S = 5L
    }
}
