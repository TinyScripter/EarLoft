package keinabersmart.kleinaberlaut.core.network.podcast.ardsounds

import keinabersmart.kleinaberlaut.core.network.await
import keinabersmart.kleinaberlaut.core.network.podcast.PODCAST_USER_AGENT
import keinabersmart.kleinaberlaut.core.network.podcast.ParsedEpisode
import keinabersmart.kleinaberlaut.core.network.podcast.ParsedFeed
import keinabersmart.kleinaberlaut.core.network.podcast.PodcastFeedException
import keinabersmart.kleinaberlaut.core.network.podcast.directory.DirectoryPodcast
import keinabersmart.kleinaberlaut.core.network.podcast.directory.PodcastDirectory
import keinabersmart.kleinaberlaut.core.network.podcast.normalizeFeedUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Abo-Adressen von Sendungen aus ARD Sounds, die über GraphQL statt über einen RSS-Feed kommen (Konzept 12.10). */
const val ARD_SOUNDS_SCHEME = "ardsounds:"

/** Die Abo-Adresse einer Sendung. */
fun ardSoundsFeedUrl(showId: String): String = ARD_SOUNDS_SCHEME + showId

/** Die Kennung der Sendung, wenn [url] eine ARD-Sounds-Abo-Adresse ist, sonst `null`. */
fun ardSoundsShowId(url: String): String? =
    url.trim().takeIf { it.startsWith(ARD_SOUNDS_SCHEME, ignoreCase = true) }
        ?.substring(ARD_SOUNDS_SCHEME.length)?.trim()?.ifEmpty { null }

/** Link der Sendungsseite in ARD Sounds; die Seite leitet auf ihre Adresse mit Namen weiter. */
fun ardSoundsPageUrl(showId: String): String = "https://www.ardsounds.de/sendung/$showId/"

/**
 * Macht aus einer Eingabe die Abo-Adresse einer Sendung: eine Abo-Adresse selbst oder der Link einer Sendungsseite
 * (`https://www.ardsounds.de/sendung/<name>/urn:ard:show:<kennung>/`, auch unter dem alten Namen ardaudiothek.de).
 * `null`, wenn die Eingabe nichts davon ist.
 */
fun parseArdSoundsInput(input: String): String? {
    ardSoundsShowId(input)?.let { return ardSoundsFeedUrl(it) }
    val url = input.trim().let { if ("://" in it) it else "https://$it" }.toHttpUrlOrNull() ?: return null
    val host = url.host.removePrefix("www.")
    if (host != "ardsounds.de" && host != "ardaudiothek.de") return null
    if (url.pathSegments.firstOrNull() != "sendung") return null
    return url.pathSegments.firstNotNullOfOrNull { SHOW_URN.matchEntire(it)?.value }?.let(::ardSoundsFeedUrl)
}

private val SHOW_URN = Regex("urn:ard:show:[0-9a-f]+", RegexOption.IGNORE_CASE)

/**
 * Inoffizielle GraphQL-Schnittstelle von ARD Sounds (vormals ARD Audiothek), wie sie die App der ARD nutzt. Sie kann sich
 * ohne Ankündigung ändern; jeder Fehler – auch ein unerwarteter Aufbau der Antwort – kommt deshalb als [IOException],
 * damit er nur die Suche in ARD Sounds bzw. die Abos von dort trifft.
 */
@Singleton
class ArdSoundsApi(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl = DEFAULT_ENDPOINT,
) {
    @Inject
    constructor(client: OkHttpClient) : this(client, DEFAULT_ENDPOINT)

    /**
     * Sendungen zu [term]. Nennt ARD Sounds für eine Sendung einen öffentlichen RSS-Feed, trägt der Treffer dessen
     * Adresse (so wird ein gewöhnliches Abo daraus, das auch andere Verzeichnisse kennen); sonst die Abo-Adresse
     * `ardsounds:<Kennung>`.
     */
    suspend fun search(term: String): List<DirectoryPodcast> {
        val variables = buildJsonObject {
            put("query", term)
            put("limit", SEARCH_LIMIT)
        }
        val data = query(SEARCH_QUERY, variables, SearchData.serializer())
        return data.search?.programSets?.nodes.orEmpty()
            .filter { (it.numberOfElements ?: 1) > 0 }
            .mapNotNull { show ->
                val showId = show.coreId ?: show.id ?: return@mapNotNull null
                DirectoryPodcast(
                    title = show.title?.trim().orEmpty().ifEmpty { return@mapNotNull null },
                    author = show.publicationService?.title?.trim().orEmpty(),
                    feedUrl = show.feedUrl?.let(::normalizeFeedUrl)?.toString() ?: ardSoundsFeedUrl(showId),
                    imageUrl = show.image?.sized(),
                    directories = setOf(PodcastDirectory.ARD_SOUNDS),
                )
            }
    }

    /** Die Sendung [showId] mit ihren neuesten (höchstens [EPISODE_LIMIT]) Folgen, im selben Format wie ein RSS-Feed. */
    suspend fun fetch(showId: String): ParsedFeed {
        val variables = buildJsonObject {
            put("id", showId)
            put("first", EPISODE_LIMIT)
        }
        val show = query(SHOW_QUERY, variables, ShowData.serializer()).show
            ?: throw PodcastFeedException("Keine Sendung $showId in ARD Sounds")
        val feedImage = show.image?.sized()
        return ParsedFeed(
            title = show.title?.trim().orEmpty(),
            author = show.publicationService?.title?.trim().orEmpty(),
            description = show.description?.ifBlank { null } ?: show.synopsis.orEmpty(),
            imageUrl = feedImage,
            episodes = show.items?.nodes.orEmpty().mapNotNull { it.toEpisode() },
        )
    }

    private fun Episode.toEpisode(): ParsedEpisode? {
        val guid = coreId ?: id ?: return null
        val audio = audios.orEmpty()
        // Eine Fassung zum Herunterladen ist die stabilere Datei; Sendungen ohne sie gibt es nur als Stream.
        val url = audio.firstNotNullOfOrNull { a -> a.downloadUrl?.takeIf { a.allowDownload == true } }
            ?: audio.firstNotNullOfOrNull { it.url }
            ?: return null
        val mime = audio.firstOrNull { it.url == url || it.downloadUrl == url }?.mimeType
        return ParsedEpisode(
            guid = guid,
            title = title?.trim().orEmpty(),
            description = showNotes?.ifBlank { null } ?: description?.ifBlank { null } ?: summary.orEmpty(),
            audioUrl = url,
            mimeType = if (mime.equals("audio/mp3", ignoreCase = true)) "audio/mpeg" else mime,
            durationMs = (duration ?: 0) * 1000L,
            publishedAt = publishDate?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0,
            imageUrl = image?.sized(),
            chaptersUrl = null,
        )
    }

    private suspend fun <T> query(query: String, variables: JsonObject, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(endpoint).post(body)
            .header("User-Agent", PODCAST_USER_AGENT)
            .header("Accept", "application/json")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val text = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.peekBody(MAX_RESPONSE_BYTES).string()
        }
        try {
            val answer = JSON.decodeFromString(GraphQlResponse.serializer(), text)
            val data = answer.data?.takeIf { it is JsonObject }
                ?: throw IOException("ARD Sounds: ${answer.errors?.firstOrNull()?.message ?: "Antwort ohne Daten"}")
            JSON.decodeFromJsonElement(serializer, data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Auch IllegalArgumentException aus der Deserialisierung: Die Schnittstelle hat sich geändert.
            throw IOException("Unlesbare Antwort von ARD Sounds", e)
        }
    }

    private companion object {
        val DEFAULT_ENDPOINT = "https://api.ardaudiothek.de/graphql".toHttpUrl()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
        const val SEARCH_LIMIT = 50
        const val EPISODE_LIMIT = 500
        const val MAX_RESPONSE_BYTES = 10L * 1024 * 1024

        /** Breite der Bilder; die Adressen enthalten `{width}` als Platzhalter. */
        const val IMAGE_WIDTH = 600

        val SEARCH_QUERY = """
            query(${'$'}query: String, ${'$'}limit: Int) {
              search(query: ${'$'}query, limit: ${'$'}limit, type: ProgramSets) {
                programSets { nodes { id coreId title numberOfElements feedUrl image { url url1X1 } publicationService { title } } }
              }
            }
        """.trimIndent()

        val SHOW_QUERY = """
            query(${'$'}id: ID!, ${'$'}first: Int) {
              show(id: ${'$'}id) {
                id coreId title description synopsis image { url url1X1 } publicationService { title }
                items(first: ${'$'}first, orderBy: PUBLISH_DATE_DESC, condition: { isPublished: true }) {
                  nodes {
                    id coreId title publishDate duration description showNotes summary image { url url1X1 }
                    audios { url downloadUrl allowDownload mimeType }
                  }
                }
              }
            }
        """.trimIndent()

        /** Quadratisches Bild bevorzugt, in [IMAGE_WIDTH] Pixeln Breite. */
        fun Image.sized(): String? =
            (url1X1 ?: url)?.trim()?.ifEmpty { null }?.replace("{width}", IMAGE_WIDTH.toString())
    }
}

@Serializable
private data class GraphQlResponse(val data: JsonElement? = null, val errors: List<GraphQlError>? = null)

@Serializable
private data class GraphQlError(val message: String? = null)

@Serializable
private data class SearchData(val search: SearchResult? = null)

@Serializable
private data class SearchResult(val programSets: ShowConnection? = null)

@Serializable
private data class ShowConnection(val nodes: List<Show>? = null)

@Serializable
private data class ShowData(val show: Show? = null)

@Serializable
private data class Show(
    val id: String? = null,
    val coreId: String? = null,
    val title: String? = null,
    val numberOfElements: Int? = null,
    val feedUrl: String? = null,
    val description: String? = null,
    val synopsis: String? = null,
    val image: Image? = null,
    val publicationService: PublicationService? = null,
    val items: EpisodeConnection? = null,
)

@Serializable
private data class PublicationService(val title: String? = null)

@Serializable
private data class Image(val url: String? = null, val url1X1: String? = null)

@Serializable
private data class EpisodeConnection(val nodes: List<Episode>? = null)

@Serializable
private data class Episode(
    val id: String? = null,
    val coreId: String? = null,
    val title: String? = null,
    val publishDate: String? = null,
    val duration: Long? = null,
    val description: String? = null,
    val showNotes: String? = null,
    val summary: String? = null,
    val image: Image? = null,
    val audios: List<Audio>? = null,
)

@Serializable
private data class Audio(
    val url: String? = null,
    val downloadUrl: String? = null,
    val allowDownload: Boolean? = null,
    val mimeType: String? = null,
)
