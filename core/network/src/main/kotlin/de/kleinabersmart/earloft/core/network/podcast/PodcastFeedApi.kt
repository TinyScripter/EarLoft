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
 * The feed's response to a conditional fetch. [movedTo]: the feed has permanently moved to another URL (only
 * 301/308 along the whole way, no switch from HTTPS to HTTP; concept 12.10 "feed move").
 */
sealed interface FeedFetch {
    val movedTo: String?

    /** Unchanged since the last fetch (HTTP 304). */
    data class NotModified(override val movedTo: String? = null) : FeedFetch

    data class Fetched(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?,
        override val movedTo: String? = null,
    ) : FeedFetch
}

/** A downloaded transcript, not yet parsed; [contentType] as the server reports it. */
data class TranscriptText(val text: String, val contentType: String?)

/** A chapter from the Podcasting 2.0 chapter list or Podlove; image and web page only as an absolute http(s) URL. */
data class ParsedChapter(val startMs: Long, val title: String, val imageUrl: String? = null, val url: String? = null)

/** Only absolute http(s) URLs count as a web page or image. */
internal fun webAddress(raw: String?): String? =
    raw?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }

/**
 * Fetching of feeds, chapter lists and images. Feeds and chapters never come from the HTTP cache. [FeedAuth] authenticates
 * against the protected feed ([FeedAuthInterceptor]); without it the host's stored credentials apply.
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
            if (!response.isSuccessful) throw HttpStatusException(response.code, movedTo)
            // A feed that is too large is read truncated and rejected by the parser as incomplete.
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
     * Text of a transcript (`podcast:transcript`) including the response's `Content-Type`. May come from the HTTP cache; on the
     * host of a protected feed [FeedAuthInterceptor] authenticates. One that is too large is not truncated but rejected.
     */
    suspend fun transcript(url: String): TranscriptText = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("Ungültige Transkript-URL")
        val request = Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.peekBody(MAX_TRANSCRIPT_BYTES + 1)
            if (body.contentLength() > MAX_TRANSCRIPT_BYTES) throw IOException("Transkript größer als $MAX_TRANSCRIPT_BYTES Bytes")
            TranscriptText(body.string(), response.header("Content-Type"))
        }
    }

    /**
     * Request for the audio file of an episode (own User-Agent, never from the HTTP cache); `null` for an invalid URL.
     * [auth]: the credentials of its subscription (only valid on its host).
     */
    fun audioRequest(url: String, auth: FeedAuth? = null): Request? =
        url.toHttpUrlOrNull()?.let {
            Request.Builder().url(it).header("User-Agent", USER_AGENT).cacheControl(CacheControl.Builder().noStore().build())
                .tag(FeedAuth::class.java, auth)
                .build()
        }

    /** Image bytes; `null` if the image doesn't exist (anymore). */
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
 * Target of the redirects before [response], as long as they were all permanent starting from [requested] (301/308); a
 * temporary one (302/307) ends the chain. `null` if there is none or it led from HTTPS to HTTP.
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
 * Some directories (e.g. podcastindex.org) and audio hosts (Buzzsprout via podtrac/op3) reject OkHttp's default User-Agent
 * with 403; that's why streams in the player send this one too.
 */
const val PODCAST_USER_AGENT = "EarLoft/1.0 (Android podcast client)"

/**
 * Turns an input into a feed URL: whitespace removed, `feed://`/`itpc://`/`pcast://` become `https://`, without
 * a scheme `https://` applies. `null` if this doesn't result in an http(s) URL.
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
 * Podcasting 2.0 chapters (`{"chapters":[{"startTime":0,"title":"…","img":"…","url":"…"}]}`), sorted by start.
 * "Silent" chapters (`"toc": false`, e.g. an image for a donation in the middle of a chapter) don't belong in the table of contents
 * and would otherwise cut up the chapter they're in; they are dropped.
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
