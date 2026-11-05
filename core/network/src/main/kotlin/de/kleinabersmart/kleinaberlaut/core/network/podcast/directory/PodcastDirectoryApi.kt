package de.kleinabersmart.kleinaberlaut.core.network.podcast.directory

import de.kleinabersmart.kleinaberlaut.core.network.BuildConfig
import de.kleinabersmart.kleinaberlaut.core.network.await
import de.kleinabersmart.kleinaberlaut.core.network.podcast.PODCAST_USER_AGENT
import de.kleinabersmart.kleinaberlaut.core.network.podcast.ardsounds.ArdSoundsApi
import de.kleinabersmart.kleinaberlaut.core.network.podcast.normalizeFeedUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Zugang zu den Verzeichnissen, die einen Schlüssel brauchen oder erst freigeschaltet werden (Konzept 12.10). */
internal data class DirectoryAccess(
    val podcastIndexKey: String,
    val podcastIndexSecret: String,
    val fyydEnabled: Boolean,
) {
    fun allows(directory: PodcastDirectory): Boolean = when (directory) {
        PodcastDirectory.APPLE -> true
        PodcastDirectory.PODCAST_INDEX -> podcastIndexKey.isNotBlank() && podcastIndexSecret.isNotBlank()
        PodcastDirectory.FYYD -> fyydEnabled
        PodcastDirectory.ARD_SOUNDS -> true
    }

    companion object {
        /** Aus `local.properties` (`podcastindex.key`, `podcastindex.secret`, `fyyd.enabled`), siehe `core/network/build.gradle.kts`. */
        fun fromBuild() = DirectoryAccess(BuildConfig.PODCAST_INDEX_KEY, BuildConfig.PODCAST_INDEX_SECRET, BuildConfig.FYYD_ENABLED)
    }
}

/** Basisadressen der Verzeichnisse; in Tests ein MockWebServer. */
internal data class DirectoryEndpoints(
    val apple: HttpUrl = "https://itunes.apple.com/search".toHttpUrl(),
    val podcastIndex: HttpUrl = "https://api.podcastindex.org/api/1.0/search/byterm".toHttpUrl(),
    val fyyd: HttpUrl = "https://api.fyyd.de/0.2/search/podcast".toHttpUrl(),
)

/** Suche in öffentlichen Podcast-Verzeichnissen. Jede Suche ist eine einzelne Anfrage, ohne Blättern. */
@Singleton
class PodcastDirectoryApi internal constructor(
    private val client: OkHttpClient,
    private val endpoints: DirectoryEndpoints,
    private val access: DirectoryAccess,
    private val ardSounds: ArdSoundsApi,
    private val clock: () -> Long,
) {
    @Inject
    constructor(client: OkHttpClient, ardSounds: ArdSoundsApi) :
        this(client, DirectoryEndpoints(), DirectoryAccess.fromBuild(), ardSounds, System::currentTimeMillis)

    /** Die Verzeichnisse, die dieser Build nutzen darf, in Anzeigereihenfolge. */
    val available: List<PodcastDirectory> = PodcastDirectory.entries.filter(access::allows)

    /**
     * Podcasts zu [term] aus [directory]; [country] (ISO 3166, z. B. `DE`) gilt nur für Apple. Treffer ohne gültige
     * Feed-Adresse fallen weg. Wirft [IOException] bei Netz-, HTTP- oder Formatfehlern.
     */
    suspend fun search(directory: PodcastDirectory, term: String, country: String): List<DirectoryPodcast> =
        withContext(Dispatchers.IO) {
            check(access.allows(directory)) { "Verzeichnis $directory ist in diesem Build nicht freigeschaltet" }
            try {
                when (directory) {
                    PodcastDirectory.APPLE -> apple(term, country)
                    PodcastDirectory.PODCAST_INDEX -> podcastIndex(term)
                    PodcastDirectory.FYYD -> fyyd(term)
                    PodcastDirectory.ARD_SOUNDS -> ardSounds.search(term)
                }
            } catch (e: SerializationException) {
                throw IOException("Unlesbare Antwort von $directory", e)
            }
        }

    private suspend fun apple(term: String, country: String): List<DirectoryPodcast> {
        val url = endpoints.apple.newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("term", term)
            .addQueryParameter("country", country)
            .addQueryParameter("limit", LIMIT.toString())
            .build()
        val response = JSON.decodeFromString<AppleResponse>(get(Request.Builder().url(url)))
        return response.results.mapNotNull {
            hit(it.collectionName, it.artistName, it.feedUrl, it.artworkUrl600 ?: it.artworkUrl100, PodcastDirectory.APPLE)
        }
    }

    private suspend fun podcastIndex(term: String): List<DirectoryPodcast> {
        val url = endpoints.podcastIndex.newBuilder()
            .addQueryParameter("q", term)
            .addQueryParameter("max", LIMIT.toString())
            .build()
        val date = (clock() / 1000).toString()
        val request = Request.Builder().url(url)
            .header("X-Auth-Key", access.podcastIndexKey)
            .header("X-Auth-Date", date)
            .header("Authorization", sha1Hex(access.podcastIndexKey + access.podcastIndexSecret + date))
        val response = JSON.decodeFromString<PodcastIndexResponse>(get(request))
        return response.feeds.mapNotNull {
            hit(it.title, it.author, it.url, it.artwork?.ifBlank { null } ?: it.image, PodcastDirectory.PODCAST_INDEX)
        }
    }

    private suspend fun fyyd(term: String): List<DirectoryPodcast> {
        val url = endpoints.fyyd.newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("count", LIMIT.toString())
            .build()
        val response = JSON.decodeFromString<FyydResponse>(get(Request.Builder().url(url)))
        return response.data.mapNotNull {
            hit(it.title, it.author, it.xmlUrl, it.smallImageUrl ?: it.imgUrl, PodcastDirectory.FYYD)
        }
    }

    private suspend fun get(request: Request.Builder): String =
        client.newCall(request.header("User-Agent", PODCAST_USER_AGENT).header("Accept", "application/json").build()).await()
            .use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.peekBody(MAX_RESPONSE_BYTES).string()
            }

    private fun hit(title: String?, author: String?, feedUrl: String?, imageUrl: String?, directory: PodcastDirectory): DirectoryPodcast? {
        val url = normalizeFeedUrl(feedUrl.orEmpty()) ?: return null
        return DirectoryPodcast(
            title = title?.trim().orEmpty().ifEmpty { url.host },
            author = author?.trim().orEmpty(),
            feedUrl = url.toString(),
            imageUrl = imageUrl?.trim()?.ifEmpty { null },
            directories = setOf(directory),
        )
    }

    private companion object {
        const val LIMIT = 50
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

        fun sha1Hex(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
private data class AppleResponse(val results: List<AppleResult> = emptyList())

@Serializable
private data class AppleResult(
    val collectionName: String? = null,
    val artistName: String? = null,
    val feedUrl: String? = null,
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
)

@Serializable
private data class PodcastIndexResponse(val feeds: List<PodcastIndexFeed> = emptyList())

@Serializable
private data class PodcastIndexFeed(
    val title: String? = null,
    val author: String? = null,
    val url: String? = null,
    val image: String? = null,
    val artwork: String? = null,
)

@Serializable
private data class FyydResponse(val data: List<FyydPodcast> = emptyList())

@Serializable
private data class FyydPodcast(
    val title: String? = null,
    val author: String? = null,
    @SerialName("xmlURL") val xmlUrl: String? = null,
    @SerialName("imgURL") val imgUrl: String? = null,
    @SerialName("smallImageURL") val smallImageUrl: String? = null,
)
