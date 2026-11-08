package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Antwort des Feeds auf einen bedingten Abruf. */
sealed interface FeedFetch {
    /** Seit dem letzten Abruf unverändert (HTTP 304). */
    data object NotModified : FeedFetch

    data class Fetched(val feed: ParsedFeed, val etag: String?, val lastModified: String?) : FeedFetch
}

/** Ein Kapitel aus der Podcasting-2.0-Kapitelliste. */
data class ParsedChapter(val startMs: Long, val title: String)

/** Abruf von Feeds, Kapitellisten und Bildern. Feeds und Kapitel kommen nie aus dem HTTP-Cache. */
@Singleton
class PodcastFeedApi @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun fetch(url: HttpUrl, etag: String? = null, lastModified: String? = null): FeedFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        client.newCall(request).await().use { response ->
            if (response.code == 304) return@use FeedFetch.NotModified
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            // Ein zu großer Feed wird abgeschnitten gelesen und vom Parser als unvollständig abgelehnt.
            val feed = PodcastFeedParser.parse(response.peekBody(MAX_FEED_BYTES).byteStream())
            FeedFetch.Fetched(feed, response.header("ETag"), response.header("Last-Modified"))
        }
    }

    suspend fun chapters(url: String): List<ParsedChapter> = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("Ungültige Kapitel-URL")
        val request = Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).cacheControl(CacheControl.FORCE_NETWORK).build()
        val text = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.peekBody(MAX_CHAPTERS_BYTES).string()
        }
        parseChapters(text)
    }

    /** Anfrage für die Audiodatei einer Folge (eigener User-Agent, nie aus dem HTTP-Cache); `null` bei ungültiger Adresse. */
    fun audioRequest(url: String): Request? =
        url.toHttpUrlOrNull()?.let {
            Request.Builder().url(it).header("User-Agent", USER_AGENT).cacheControl(CacheControl.Builder().noStore().build()).build()
        }

    /** Bild-Bytes; `null`, wenn es das Bild nicht (mehr) gibt. */
    suspend fun image(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext null
        client.newCall(Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).build()).await().use { response ->
            if (response.code == 404 || response.code == 410) return@use null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.peekBody(MAX_IMAGE_BYTES).bytes()
        }
    }

    private companion object {
        const val USER_AGENT = PODCAST_USER_AGENT
        const val MAX_FEED_BYTES = 20L * 1024 * 1024
        const val MAX_CHAPTERS_BYTES = 1L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
    }
}

/** Manche Verzeichnisse (z. B. podcastindex.org) lehnen den Standard-User-Agent von OkHttp mit 403 ab. */
internal const val PODCAST_USER_AGENT = "EarLoft/1.0 (Android podcast client)"

/**
 * Macht aus einer Eingabe eine Feed-Adresse: Leerraum weg, `feed://`/`itpc://`/`pcast://` werden `https://`, ohne
 * Schema gilt `https://`. `null`, wenn daraus keine http(s)-Adresse wird.
 */
fun normalizeFeedUrl(input: String): HttpUrl? {
    var text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    text = text.replace(Regex("^(feed|itpc|pcast|podcast)://", RegexOption.IGNORE_CASE), "https://")
    if (!text.contains("://")) text = "https://$text"
    val url = text.toHttpUrlOrNull() ?: return null
    return if (url.host.contains('.') || url.host == "localhost") url else null
}

@Serializable
private data class ChaptersFile(val chapters: List<ChapterDto> = emptyList())

@Serializable
private data class ChapterDto(val startTime: Double = 0.0, val title: String = "")

/** Podcasting-2.0-Kapitel (`{"chapters":[{"startTime":0,"title":"…"}]}`), nach Beginn sortiert. */
fun parseChapters(json: String): List<ParsedChapter> =
    try {
        CHAPTER_JSON.decodeFromString<ChaptersFile>(json).chapters
            .filter { it.startTime >= 0 }
            .sortedBy { it.startTime }
            .map { ParsedChapter((it.startTime * 1000).toLong(), it.title.trim()) }
    } catch (e: SerializationException) {
        throw IOException("Unlesbare Kapitelliste", e)
    }

private val CHAPTER_JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
