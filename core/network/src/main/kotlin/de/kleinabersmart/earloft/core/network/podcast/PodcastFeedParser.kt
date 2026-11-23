package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.model.EpisodePerson
import de.kleinabersmart.earloft.core.model.EpisodeType
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

/** Not a readable RSS or Atom feed. */
class PodcastFeedException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
    /** Web page of the podcast (`<link>`), for sharing (concept 13). */
    val websiteUrl: String? = null,
    /** New URL the feed announces itself (`<itunes:new-feed-url>`), only http(s). */
    val newFeedUrl: String? = null,
    /** Stable ID of the podcast (`<podcast:guid>`), independent of the URL; lower-cased. */
    val podcastGuid: String? = null,
    /** First support page (`<podcast:funding url="…">Text</podcast:funding>`), only http(s); the text may be empty. */
    val fundingUrl: String? = null,
    val fundingLabel: String = "",
)

data class ParsedEpisode(
    /** Stable ID from the feed (`guid` or `id`); if missing, the audio URL is used. */
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val mimeType: String?,
    /** 0 if the feed doesn't specify a duration. */
    val durationMs: Long,
    /** Unix ms; 0 if the feed has no readable date. */
    val publishedAt: Long,
    val imageUrl: String?,
    /** Podcasting 2.0 chapter list (JSON), if present. */
    val chaptersUrl: String?,
    /** Web page of the episode (`<link>`, otherwise the `guid` if it's a permalink), for sharing (concept 13). */
    val webUrl: String? = null,
    /** Chapters directly in the feed (Podlove Simple Chapters), ordered by start; empty if the feed lists none. */
    val chapters: List<ParsedChapter> = emptyList(),
    /** Season and number (`itunes:season`/`episode`, otherwise `podcast:season`/`episode`), only whole numbers from 0. */
    val season: Int? = null,
    val episodeNumber: Int? = null,
    /** `itunes:episodeType`; `null` if the feed specifies nothing or something unknown (treated like "full"). */
    val episodeType: EpisodeType? = null,
    /** Contributors of the episode (`podcast:person`); if the episode names none, those of the podcast. */
    val persons: List<EpisodePerson> = emptyList(),
    /** The most readable transcript (`podcast:transcript`, see [pickTranscript]) and its MIME type. */
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
)

/**
 * Reads RSS 2.0 (with iTunes and Podcasting 2.0 extensions) and Atom (concept 12.1). The feed is first read into a
 * small tree; that's harmless for podcast feeds (a few MB) and keeps the evaluation readable.
 */
object PodcastFeedParser {
    private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"
    private const val NS_ATOM = "http://www.w3.org/2005/Atom"
    private const val NS_PSC = "http://podlove.org/simple-chapters"
    private const val NS_NONE = ""

    /** @param charset from the HTTP `Content-Type`; applies only if the document itself names no encoding. */
    fun parse(input: InputStream, charset: String? = null): ParsedFeed {
        val root = try {
            readTree(input, charset)
        } catch (e: XmlPullParserException) {
            throw PodcastFeedException("Not valid XML", e)
        }
        return when (root?.name) {
            "rss" -> parseRss(root)
            "feed" -> parseAtom(root)
            else -> throw PodcastFeedException("Neither RSS nor Atom")
        }
    }

    private fun parseRss(rss: Node): ParsedFeed {
        val channel = rss.child("channel") ?: throw PodcastFeedException("RSS without channel")
        val feedPersons = channel.persons()
        val episodes = channel.children("item").mapNotNull { item ->
            val enclosure = item.child("enclosure")
            val url = enclosure?.attr("url")?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            val guid = item.child("guid")
            ParsedEpisode(
                guid = item.text("guid").ifEmpty { url },
                title = item.plain("title").ifEmpty { item.plain("title", NS_ITUNES) },
                description = item.text("encoded", NS_CONTENT).ifEmpty { item.text("description") }.ifEmpty { item.text("summary", NS_ITUNES) },
                audioUrl = url,
                mimeType = enclosure?.attr("type")?.takeIf { it.isNotBlank() },
                durationMs = parseDuration(item.text("duration", NS_ITUNES)),
                publishedAt = parseRfc1123(item.text("pubDate")),
                imageUrl = item.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() },
                chaptersUrl = item.podcastChild("chapters")?.attr("url")?.takeIf { it.isNotBlank() },
                webUrl = webPage(item.text("link", NS_NONE))
                    ?: guid?.takeIf { it.attr("isPermaLink")?.trim() != "false" }?.let { webPage(it.text.toString()) },
                chapters = item.simpleChapters(),
            ).withPodcasting20(item, feedPersons)
        }
        return ParsedFeed(
            title = channel.plain("title"),
            author = channel.plain("author", NS_ITUNES),
            description = channel.text("description").ifEmpty { channel.text("summary", NS_ITUNES) },
            imageUrl = channel.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() }
                ?: channel.child("image", NS_NONE)?.text("url")?.takeIf { it.isNotBlank() },
            episodes = episodes,
            websiteUrl = webPage(channel.text("link", NS_NONE)),
            newFeedUrl = webAddress(channel.text("new-feed-url", NS_ITUNES)),
        ).withPodcasting20(channel)
    }

    private fun parseAtom(feed: Node): ParsedFeed {
        val feedPersons = feed.persons()
        val episodes = feed.children("entry").mapNotNull { entry ->
            val enclosure = entry.children("link").firstOrNull { it.attr("rel") == "enclosure" }
            val url = enclosure?.attr("href")?.trim().orEmpty()
            if (url.isEmpty()) return@mapNotNull null
            ParsedEpisode(
                guid = entry.text("id").ifEmpty { url },
                title = entry.plain("title", NS_ATOM),
                description = entry.text("content", NS_ATOM).ifEmpty { entry.text("summary", NS_ATOM) },
                audioUrl = url,
                mimeType = enclosure?.attr("type")?.takeIf { it.isNotBlank() },
                durationMs = parseDuration(entry.text("duration", NS_ITUNES)),
                publishedAt = parseIso(entry.text("published").ifEmpty { entry.text("updated") }),
                imageUrl = entry.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() },
                chaptersUrl = entry.podcastChild("chapters")?.attr("url")?.takeIf { it.isNotBlank() },
                webUrl = entry.alternateLink(),
                chapters = entry.simpleChapters(),
            ).withPodcasting20(entry, feedPersons)
        }
        return ParsedFeed(
            title = feed.plain("title", NS_ATOM),
            author = feed.plain("author", NS_ITUNES).ifEmpty { feed.child("author", NS_ATOM)?.plain("name").orEmpty() },
            description = feed.text("subtitle", NS_ATOM).ifEmpty { feed.text("summary", NS_ITUNES) },
            imageUrl = feed.child("image", NS_ITUNES)?.attr("href")?.takeIf { it.isNotBlank() }
                ?: feed.text("logo").ifEmpty { feed.text("icon") }.takeIf { it.isNotBlank() },
            episodes = episodes,
            websiteUrl = feed.alternateLink(),
            newFeedUrl = webAddress(feed.text("new-feed-url", NS_ITUNES)),
        ).withPodcasting20(feed)
    }

    /** `podcast:guid` and `podcast:funding` of the channel or Atom feed (concept 12.10). */
    private fun ParsedFeed.withPodcasting20(channel: Node): ParsedFeed {
        val funding = channel.podcastChildren("funding").firstOrNull { webAddress(it.attr("url")) != null }
        return copy(
            podcastGuid = channel.podcastChild("guid")?.text?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            fundingUrl = funding?.let { webAddress(it.attr("url")) },
            fundingLabel = funding?.let { decodeHtmlEntities(it.text.toString().trim()) }.orEmpty(),
        )
    }

    /**
     * Season, number, type, contributors and transcript of an episode (concept 12.5 "Podcasting 2.0"). iTunes takes precedence
     * for season and number because almost all feeds include it; `podcast:episode` may also be "12.5", which doesn't count as a number.
     */
    private fun ParsedEpisode.withPodcasting20(item: Node, feedPersons: List<EpisodePerson>): ParsedEpisode {
        val transcript = pickTranscript(
            item.podcastChildren("transcript").mapNotNull { node -> webAddress(node.attr("url"))?.let { it to node.attr("type") } },
        )
        return copy(
            season = wholeNumber(item.text("season", NS_ITUNES)) ?: wholeNumber(item.podcastChild("season")?.text?.toString()),
            episodeNumber = wholeNumber(item.text("episode", NS_ITUNES)) ?: wholeNumber(item.podcastChild("episode")?.text?.toString()),
            episodeType = when (item.text("episodeType", NS_ITUNES).lowercase()) {
                "full" -> EpisodeType.FULL
                "trailer" -> EpisodeType.TRAILER
                "bonus" -> EpisodeType.BONUS
                else -> null
            },
            persons = item.persons().ifEmpty { feedPersons },
            transcriptUrl = transcript?.first,
            transcriptType = transcript?.second,
        )
    }

    /** `<podcast:person role="guest" group="cast" img="…" href="…">Name</podcast:person>`; without a name it's dropped. */
    private fun Node.persons(): List<EpisodePerson> =
        podcastChildren("person").mapNotNull { node ->
            val name = decodeHtmlEntities(node.text.toString().trim()).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            EpisodePerson(
                name = name,
                role = node.attr("role")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                group = node.attr("group")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                imageUrl = webAddress(node.attr("img")),
                url = webAddress(node.attr("href")),
            )
        }.distinct()

    /** "3" or "3.0"; fractions, negative numbers and everything else: `null`. */
    private fun wholeNumber(raw: String?): Int? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        return value.takeIf { it >= 0 && it % 1.0 == 0.0 && it <= Int.MAX_VALUE }?.toInt()
    }

    /** Atom: `link` with `rel="alternate"` (or without `rel`), preferably as HTML. */
    private fun Node.alternateLink(): String? {
        val links = children("link", NS_ATOM).filter { it.attr("rel").isNullOrBlank() || it.attr("rel") == "alternate" }
        val link = links.firstOrNull { it.attr("type")?.contains("html") == true } ?: links.firstOrNull()
        return webPage(link?.attr("href"))
    }

    private fun webPage(raw: String?): String? = webAddress(raw)

    /**
     * Podlove Simple Chapters (`<psc:chapters><psc:chapter start="00:01:02.500" title="…" href="…" image="…"/>`), as
     * many feeds generated with Podlove Publisher include them in the item. Chapters without a readable start are dropped.
     */
    private fun Node.simpleChapters(): List<ParsedChapter> =
        child("chapters", NS_PSC)?.children("chapter", NS_PSC).orEmpty()
            .mapNotNull { chapter ->
                val start = parseClock(chapter.attr("start").orEmpty().trim().removePrefix("npt:")) ?: return@mapNotNull null
                ParsedChapter(start, decodeHtmlEntities(chapter.attr("title")?.trim().orEmpty()), webAddress(chapter.attr("image")), webAddress(chapter.attr("href")))
            }
            .sortedBy { it.startMs }

    /** "1:02:03", "12:34", "754" or "754.5" (seconds); everything else counts as unknown (0). */
    internal fun parseDuration(raw: String): Long = parseClock(raw) ?: 0

    /** Like [parseDuration], but `null` instead of 0 for anything that isn't a time value. */
    private fun parseClock(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (raw.isBlank() || parts.size > 3) return null
        val numbers = parts.map { it.trim().toDoubleOrNull() ?: return null }
        val seconds = numbers.fold(0.0) { total, part -> total * 60 + part }
        return if (seconds >= 0) (seconds * 1000).roundToLong() else null
    }

    internal fun parseRfc1123(raw: String): Long {
        val strict = try {
            ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
        } catch (_: DateTimeParseException) {
            null
        }
        // The strict parser takes a two-digit year literally ("05 Oct 24 10:00 +0200" is the year 24); the lenient one
        // maps it to 2024.
        if (strict != null && strict.year >= MIN_FOUR_DIGIT_YEAR) return strict.toInstant().toEpochMilli()
        return parseLenientDate(raw.trim()) ?: parseIso(raw)
    }

    /**
     * What feeds write instead of strict RFC 1123 (review G13): zone names (`EST`, `UTC`, `Z`, `CEST`), a weekday that
     * doesn't match the date or is written out, two-digit years, missing seconds, German month abbreviations. Otherwise
     * the date would be 0 and, on subscribing, every episode of the feed would count as published "today".
     */
    private fun parseLenientDate(raw: String): Long? {
        val match = LENIENT_DATE.matchEntire(raw) ?: return null
        val (dayText, monthText, yearText, hourText, minuteText, secondText, zoneText) = match.destructured
        val month = MONTHS[monthText.take(3).lowercase()] ?: return null
        val year = yearText.toInt().let { if (yearText.length == 2) it + if (it < 50) 2000 else 1900 else it }
        val offset = zoneOffset(zoneText.trim()) ?: return null
        return try {
            java.time.LocalDateTime.of(year, month, dayText.toInt(), hourText.toInt(), minuteText.toInt(), secondText.ifEmpty { "0" }.toInt())
                .toInstant(offset).toEpochMilli()
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    private fun zoneOffset(zone: String): java.time.ZoneOffset? {
        if (zone.isEmpty()) return java.time.ZoneOffset.UTC
        ZONE_HOURS[zone.uppercase()]?.let { return java.time.ZoneOffset.ofHours(it) }
        NUMERIC_ZONE.matchEntire(zone)?.let { m ->
            val sign = if (m.groupValues[1] == "-") -1 else 1
            val hours = m.groupValues[2].toInt()
            val minutes = m.groupValues[3].ifEmpty { "0" }.toInt()
            return try {
                java.time.ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
            } catch (_: java.time.DateTimeException) {
                null
            }
        }
        return null
    }

    private val LENIENT_DATE = Regex(
        """(?:[A-Za-zÄÖÜäöü]+\.?,?\s*)?(\d{1,2})\.?\s+([A-Za-zÄÖÜäöü]{3,})\.?\s+(\d{4}|\d{2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*(.*)""",
    )
    /** Below this, the strict parser has read a two-digit year. */
    private const val MIN_FOUR_DIGIT_YEAR = 1000
    private val NUMERIC_ZONE = Regex("""(?:GMT|UTC)?([+-])(\d{1,2}):?(\d{2})?""")
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "mär" to 3, "mrz" to 3, "apr" to 4, "may" to 5, "mai" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "okt" to 10, "nov" to 11, "dec" to 12, "dez" to 12,
    )
    private val ZONE_HOURS = mapOf(
        "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0, "WET" to 0, "BST" to 1, "WEST" to 1, "CET" to 1, "MEZ" to 1,
        "CEST" to 2, "MESZ" to 2, "EET" to 2, "EEST" to 3, "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5,
        "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7, "AKST" to -9, "AKDT" to -8, "HST" to -10,
    )

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

    // --- Tree ---

    private class Node(val name: String, val namespace: String, val attributes: Map<String, String>) {
        val text = StringBuilder()
        val children = mutableListOf<Node>()

        fun children(name: String, namespace: String? = null): List<Node> =
            children.filter { it.name == name && (namespace == null || it.namespace == namespace) }

        /**
         * Without a namespace, any namespace matches, but the feed's own (that of this element) is preferred: `<itunes:title>`
         * before `<title>` must not win (review2 J43).
         */
        fun child(name: String, namespace: String? = null): Node? = children(name, namespace).let { found ->
            if (namespace == null) found.firstOrNull { it.namespace == this.namespace } ?: found.firstOrNull() else found.firstOrNull()
        }

        /**
         * Elements of the Podcasting 2.0 namespace. Feeds name it differently (`https://podcastindex.org/namespace/1.0`,
         * `http://podcastindex.org/namespace/1.0`, the Podcastindex GitHub documentation), so every URL
         * that contains "podcastindex" counts.
         */
        fun podcastChild(name: String): Node? = podcastChildren(name).firstOrNull()

        fun podcastChildren(name: String): List<Node> =
            children.filter { it.name == name && it.namespace.contains("podcastindex", ignoreCase = true) }

        fun text(name: String, namespace: String? = null): String = child(name, namespace)?.text?.toString()?.trim().orEmpty()

        /**
         * Plain text (title, author): like [text], plus HTML entities resolved once. Many feeds double-encode
         * (`&amp;amp;` or `&amp;` in CDATA), then "&amp;" would remain in the title after the XML parser.
         */
        fun plain(name: String, namespace: String? = null): String = decodeHtmlEntities(text(name, namespace))

        fun attr(name: String): String? = attributes[name]
    }

    private fun readTree(input: InputStream, charset: String?): Node? {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        val guarded = input.rejectingEntityDeclarations() ?: throw PodcastFeedException("Feed with custom XML entities")
        parser.setInput(guarded, charset?.takeUnless { guarded.declaresEncoding() })
        // HTML entities outside CDATA (`&nbsp;`, `&eacute;`) are not XML; without this the parser rejects the whole feed
        // (review G53).
        try {
            HTML_ONLY_ENTITIES.forEach { (name, value) -> parser.defineEntityReplacementText(name, value) }
        } catch (_: Exception) {
            // A parser without entity replacement: as before.
        }
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
                    // The parsers are lenient; a truncated feed would otherwise look like an empty one.
                    if (stack.isNotEmpty()) throw PodcastFeedException("Feed is truncated")
                    return root
                }
            }
        }
    }
}
