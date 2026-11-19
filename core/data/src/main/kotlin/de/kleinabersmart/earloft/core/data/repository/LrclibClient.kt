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
 * Lyrics from LRCLIB (lrclib.net, no key). First an entry is fetched by title, artist and duration;
 * LRCLIB only accepts recordings whose duration differs by at most 2 s, so the timings of synced lines match.
 * If there is none, a search is done and only plain text is adopted: a different version has different timings.
 *
 * [Lyrics.None] means "there is nothing there"; network errors are thrown as [IOException].
 */
@Singleton
class LrclibClient internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    @Inject
    constructor(client: OkHttpClient) : this(client, BASE_URL)

    /** [artists] in the order they are queried (e.g. first "A feat. B", then "A"). */
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
        // The search is fuzzy; only the same title and artist count, otherwise the lyrics of another song would come back.
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

    /** `optString` returned the text "null" for JSON `null`. */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name).trim()

    private fun api(path: String): HttpUrl.Builder = "$baseUrl/api/$path".toHttpUrl().newBuilder()

    /**
     * Response as text; `null` on 404 (nothing found). LRCLIB rate-limits requests per IP address and states the
     * wait time on 429 (usually 1 s); if it is short, it waits once and asks again.
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

    /** Broken responses count like a network error: don't remember as "no lyrics". */
    private inline fun <T> parsed(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("Unlesbare Antwort von LRCLIB", e)
    }

    private companion object {
        const val BASE_URL = "https://lrclib.net"
        const val USER_AGENT = "EarLoft/1.0 (Android music player)"

        /** LRCLIB rejects durations outside 1 to 3600 s for `get`. */
        const val MAX_DURATION_S = 3_600L

        /** Longer wait times after 429 count as "unreachable"; the user can try again later. */
        const val MAX_RETRY_AFTER_S = 5L
    }
}
