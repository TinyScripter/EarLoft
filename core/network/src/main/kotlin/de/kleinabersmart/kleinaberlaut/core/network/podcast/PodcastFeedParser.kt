package de.kleinabersmart.kleinaberlaut.core.network.podcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToLong

/** Kein lesbarer RSS- oder Atom-Feed. */
class PodcastFeedException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    /** Stabile Kennung aus dem Feed (`guid` bzw. `id`); fehlt sie, gilt die Audio-URL. */
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val mimeType: String?,
    /** 0, wenn der Feed keine Dauer nennt. */
    val durationMs: Long,
    /** Unix-ms; 0, wenn der Feed kein lesbares Datum hat. */
    val publishedAt: Long,
    val imageUrl: String?,
    /** Podcasting-2.0-Kapitelliste (JSON), falls vorhanden. */
    val chaptersUrl: String?,
)

/**
 * Liest RSS 2.0 (mit iTunes- und Podcasting-2.0-Erweiterungen) und Atom (Konzept 12.1). Der Feed wird zuerst in einen
 * kleinen Baum gelesen; das ist für Podcast-Feeds (einige MB) unkritisch und hält die Auswertung lesbar.
 */
object PodcastFeedParser {
    private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val NS_ATOM = "http://www.w3.org/2005/Atom"
    private const val NS_NONE = ""

    fun parse(input: InputStream): ParsedFeed {
        val root = try {
            readTree(input)
        } catch (e: XmlPullParserException) {
            throw PodcastFeedException("Kein gültiges XML", e)
        }
        return when (root?.name) {
            "rss" -> parseRss(root)
            "feed" -> parseAtom(root)
            else -> throw PodcastFeedException("Weder RSS noch Atom")
        }
    }

    private fun parseRss(rss: Node): ParsedFeed {
        val channel = rss.child("channel") ?: throw PodcastFeedException("RSS ohne channel")
        val episodes = channel.children("item").mapNotNull { item ->
            val enclosure = item.child("enclosure")
            val url = enclosure?.attr("url")?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            ParsedEpisode(
                guid = item.text("guid").ifEmpty { url },
                title = item.text("title").ifEmpty { item.text("title", NS_ITUNES) },
                description = item.text("encoded", NS_CONTENT).ifEmpty { item.text("description") }.ifEmpty { item.text("summary", NS_ITUNES) },
                audioUrl = url,
                mimeType = enclosure?.attr("type")?.takeIf { it.isNotBlank() },
                durationMs = parseDuration(item.text("duration", NS_ITUNES)),
                publishedAt = parseRfc1123(item.text("pubDate")),
                imageUrl = item.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() },
                chaptersUrl = item.podcastChild("chapters")?.attr("url")?.takeIf { it.isNotBlank() },
            )
        }
        return ParsedFeed(
            title = channel.text("title"),
            author = channel.text("author", NS_ITUNES),
            description = channel.text("description").ifEmpty { channel.text("summary", NS_ITUNES) },
            imageUrl = channel.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() }
                ?: channel.child("image", NS_NONE)?.text("url")?.takeIf { it.isNotBlank() },
            episodes = episodes,
        )
    }

    private fun parseAtom(feed: Node): ParsedFeed {
        val episodes = feed.children("entry").mapNotNull { entry ->
            val enclosure = entry.children("link").firstOrNull { it.attr("rel") == "enclosure" }
            val url = enclosure?.attr("href")?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            ParsedEpisode(
                guid = entry.text("id").ifEmpty { url },
                title = entry.text("title", NS_ATOM),
                description = entry.text("content", NS_ATOM).ifEmpty { entry.text("summary", NS_ATOM) },
                audioUrl = url,
                mimeType = enclosure?.attr("type")?.takeIf { it.isNotBlank() },
                durationMs = parseDuration(entry.text("duration", NS_ITUNES)),
                publishedAt = parseIso(entry.text("published").ifEmpty { entry.text("updated") }),
                imageUrl = entry.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() },
                chaptersUrl = entry.podcastChild("chapters")?.attr("url")?.takeIf { it.isNotBlank() },
            )
        }
        return ParsedFeed(
            title = feed.text("title", NS_ATOM),
            author = feed.text("author", NS_ITUNES).ifEmpty { feed.child("author", NS_ATOM)?.text("name").orEmpty() },
            description = feed.text("subtitle", NS_ATOM).ifEmpty { feed.text("summary", NS_ITUNES) },
            imageUrl = feed.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() }
                ?: feed.text("logo").ifEmpty { feed.text("icon") }.takeIf { it.isNotBlank() },
            episodes = episodes,
        )
    }

    /** „1:02:03“, „12:34“, „754“ oder „754.5“ (Sekunden); alles andere zählt als unbekannt (0). */
    internal fun parseDuration(raw: String): Long {
        val parts = raw.trim().split(':')
        if (raw.isBlank() || parts.size > 3) return 0
        val numbers = parts.map { it.trim().toDoubleOrNull() ?: return 0 }
        val seconds = numbers.fold(0.0) { total, part -> total * 60 + part }
        return if (seconds >= 0) (seconds * 1000).roundToLong() else 0
    }

    private fun parseRfc1123(raw: String): Long =
        try {
            ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            0
        }

    private fun parseIso(raw: String): Long =
        try {
            OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(raw.trim()).toEpochMilli()
            } catch (_: DateTimeParseException) {
                0
            }
        }

    // --- Baum ---

    private class Node(val name: String, val namespace: String, val attributes: Map<String, String>) {
        val text = StringBuilder()
        val children = mutableListOf<Node>()

        fun children(name: String, namespace: String? = null): List<Node> =
            children.filter { it.name == name && (namespace == null || it.namespace == namespace) }

        /** Ohne Namespace-Angabe gilt jeder Namespace, bevorzugt aber der des Feeds selbst. */
        fun child(name: String, namespace: String? = null): Node? = children(name, namespace).firstOrNull()

        /**
         * Elemente des Podcasting-2.0-Namespace. Feeds nennen ihn unterschiedlich (`https://podcastindex.org/namespace/1.0`,
         * `http://podcastindex.org/namespace/1.0`, die GitHub-Dokumentation des Podcastindex), deshalb zählt jede Adresse,
         * die „podcastindex“ enthält.
         */
        fun podcastChild(name: String): Node? =
            children.firstOrNull { it.name == name && it.namespace.contains("podcastindex", ignoreCase = true) }

        fun text(name: String, namespace: String? = null): String = child(name, namespace)?.text?.toString()?.trim().orEmpty()

        fun attr(name: String): String? = attributes[name]
    }

    private fun readTree(input: InputStream): Node? {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        val stack = ArrayDeque<Node>()
        var root: Node? = null
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    val attributes = HashMap<String, String>()
                    for (i in 0 until parser.attributeCount) attributes[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                    val node = Node(parser.name, parser.namespace.orEmpty(), attributes)
                    stack.lastOrNull()?.children?.add(node)
                    if (root == null) root = node
                    stack.addLast(node)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> stack.lastOrNull()?.text?.append(parser.text)
                XmlPullParser.END_TAG -> stack.removeLastOrNull()
                XmlPullParser.END_DOCUMENT -> {
                    // Die Parser sind nachsichtig; ein abgeschnittener Feed würde sonst wie ein leerer aussehen.
                    if (stack.isNotEmpty()) throw PodcastFeedException("Feed ist abgeschnitten")
                    return root
                }
            }
        }
    }
}
