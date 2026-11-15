package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.network.HttpStatusException
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
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Antwort des Feeds auf einen bedingten Abruf. [movedTo]: Der Feed liegt dauerhaft unter einer anderen Adresse (nur
 * 301/308 auf dem ganzen Weg, kein Wechsel von HTTPS zu HTTP; Konzept 12.10 „Feed-Umzug“).
 */
sealed interface FeedFetch {
    val movedTo: String?

    /** Seit dem letzten Abruf unverändert (HTTP 304). */
    data class NotModified(override val movedTo: String? = null) : FeedFetch

    data class Fetched(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?,
        override val movedTo: String? = null,
    ) : FeedFetch
}

/** Ein geladenes Transkript, noch ungelesen; [contentType] wie der Server ihn nennt. */
data class TranscriptText(val text: String, val contentType: String?)

/** Ein Kapitel aus der Podcasting-2.0-Kapitelliste oder Podlove; Bild und Webseite nur als absolute http(s)-Adresse. */
data class ParsedChapter(val startMs: Long, val title: String, val imageUrl: String? = null, val url: String? = null)

/** Nur absolute http(s)-Adressen gelten als Webseite oder Bild. */
internal fun webAddress(raw: String?): String? =
    raw?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }

/**
 * Abruf von Feeds, Kapitellisten und Bildern. Feeds und Kapitel kommen nie aus dem HTTP-Cache. [FeedAuth] meldet beim
 * geschützten Feed an ([FeedAuthInterceptor]); ohne sie gelten die gespeicherten Zugangsdaten des Hosts.
 */
@Singleton
class PodcastFeedApi @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun fetch(
        url: HttpUrl,
        etag: String? = null,
        lastModified: String? = null,
        auth: FeedAuth? = null,
    ): FeedFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .tag(FeedAuth::class.java, auth)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        client.newCall(request).await().use { response ->
            val movedTo = permanentTarget(url, response)?.toString()
            if (response.code == 304) return@use FeedFetch.NotModified(movedTo)
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            // Ein zu großer Feed wird abgeschnitten gelesen und vom Parser als unvollständig abgelehnt.
            val feed = PodcastFeedParser.parse(response.peekBody(MAX_FEED_BYTES).byteStream())
            FeedFetch.Fetched(feed, response.header("ETag"), response.header("Last-Modified"), movedTo)
        }
    }

    suspend fun chapters(url: String): List<ParsedChapter> = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("Ungültige Kapitel-URL")
        val request = Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).cacheControl(CacheControl.FORCE_NETWORK).build()
        val text = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            response.peekBody(MAX_CHAPTERS_BYTES).string()
        }
        parseChapters(text)
    }

    /**
     * Text eines Transkripts (`podcast:transcript`) samt `Content-Type` der Antwort. Darf aus dem HTTP-Cache kommen; auf dem
     * Host eines geschützten Feeds meldet [FeedAuthInterceptor] an.
     */
    suspend fun transcript(url: String): TranscriptText = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("Ungültige Transkript-URL")
        val request = Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            TranscriptText(response.peekBody(MAX_TRANSCRIPT_BYTES).string(), response.header("Content-Type"))
        }
    }

    /**
     * Anfrage für die Audiodatei einer Folge (eigener User-Agent, nie aus dem HTTP-Cache); `null` bei ungültiger Adresse.
     * [auth]: die Zugangsdaten ihres Abos (gelten nur auf dessen Host).
     */
    fun audioRequest(url: String, auth: FeedAuth? = null): Request? =
        url.toHttpUrlOrNull()?.let {
            Request.Builder().url(it).header("User-Agent", USER_AGENT).cacheControl(CacheControl.Builder().noStore().build())
                .tag(FeedAuth::class.java, auth)
                .build()
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
        const val MAX_TRANSCRIPT_BYTES = 5L * 1024 * 1024
    }
}

/**
 * Ziel der Weiterleitungen vor [response], soweit sie von [requested] aus alle dauerhaft waren (301/308); eine
 * vorübergehende (302/307) beendet die Kette. `null`, wenn es keins gibt oder es von HTTPS zu HTTP führte.
 */
internal fun permanentTarget(requested: HttpUrl, response: Response): HttpUrl? {
    val chain = generateSequence(response) { it.priorResponse }.toList().asReversed()
    var target: HttpUrl? = null
    for (i in 0 until chain.size - 1) {
        if (chain[i].code != 301 && chain[i].code != 308) break
        target = chain[i + 1].request.url
    }
    return target?.takeIf { it != requested && (it.isHttps || !requested.isHttps) }
}

/**
 * Manche Verzeichnisse (z. B. podcastindex.org) und Audio-Hoster (Buzzsprout über podtrac/op3) lehnen den Standard-User-Agent
 * von OkHttp mit 403 ab; auch Streams im Player schicken deshalb diesen.
 */
const val PODCAST_USER_AGENT = "EarLoft/1.0 (Android podcast client)"

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
private data class ChapterDto(
    val startTime: Double = 0.0,
    val title: String = "",
    val img: String? = null,
    val url: String? = null,
    val toc: Boolean = true,
)

/**
 * Podcasting-2.0-Kapitel (`{"chapters":[{"startTime":0,"title":"…","img":"…","url":"…"}]}`), nach Beginn sortiert.
 * „Stille“ Kapitel (`"toc": false`, etwa ein Bild zu einer Spende mitten im Kapitel) gehören nicht ins Inhaltsverzeichnis
 * und zerschnitten sonst das Kapitel, in dem sie liegen; sie fallen weg.
 */
fun parseChapters(json: String): List<ParsedChapter> =
    try {
        CHAPTER_JSON.decodeFromString<ChaptersFile>(json).chapters
            .filter { it.startTime >= 0 && it.toc }
            .sortedBy { it.startTime }
            .map { ParsedChapter((it.startTime * 1000).toLong(), it.title.trim(), webAddress(it.img), webAddress(it.url)) }
    } catch (e: SerializationException) {
        throw IOException("Unlesbare Kapitelliste", e)
    }

private val CHAPTER_JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }
