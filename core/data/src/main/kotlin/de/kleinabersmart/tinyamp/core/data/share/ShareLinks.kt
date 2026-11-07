package de.kleinabersmart.tinyamp.core.data.share

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Was geteilt wird: [subject] als Betreff bzw. Titel der Vorschau, [text] mit Titel und Link (Konzept 13). */
data class ShareContent(val subject: String, val text: String)

enum class MusicBrainzKind(val path: String) {
    RECORDING("recording"),
    RELEASE("release"),
    ARTIST("artist"),
}

/**
 * Links auf öffentliche, nicht-kommerzielle Verzeichnisse (Konzept 13). Die App ruft sie nie selbst ab; ohne Kennung
 * wird es ein Suchlink, den erst der Empfänger öffnet.
 */
object ShareLinks {
    private const val MUSICBRAINZ = "https://musicbrainz.org"
    private val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Seite der [mbid] bei MusicBrainz, sonst die Suche nach [title] (und [artist], wo es einen gibt). */
    fun musicBrainz(kind: MusicBrainzKind, mbid: String?, title: String, artist: String? = null): String {
        val id = mbid?.trim()?.lowercase()
        if (id != null && MBID.matches(id)) return "$MUSICBRAINZ/${kind.path}/$id"
        val terms = buildList {
            add("${kind.path}:${phrase(title)}")
            if (kind != MusicBrainzKind.ARTIST && !artist.isNullOrBlank()) add("artist:${phrase(artist)}")
        }
        return "$MUSICBRAINZ/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", terms.joinToString(" AND "))
            .addQueryParameter("type", kind.path)
            .addQueryParameter("method", "advanced")
            .build()
            .toString()
    }

    /** Eine Webseite zum Teilen: absolute http(s)-Adresse, die nicht zu einer Plattform gehört ([isPlatform]). */
    fun publicPage(url: String?): String? {
        val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
        return url.trim().takeUnless { isPlatform(parsed.host) }
    }

    /** Feed oder Audiodatei: die offene Quelle selbst, egal wo sie liegt; nur absolute http(s)-Adressen. */
    fun sourceUrl(url: String?): String? = url?.trim()?.takeIf { it.toHttpUrlOrNull() != null }

    /**
     * Händler, Streaming- und Podcast-Plattformen, auf die nie verlinkt wird (Konzept 13). Hoster bleiben: Ihre Seiten
     * gehören dem Podcast selbst.
     */
    fun isPlatform(host: String): Boolean {
        val labels = host.lowercase().removeSuffix(".").split('.')
        if (labels.dropLast(1).any { it in PLATFORM_NAMES }) return true
        return PLATFORM_DOMAINS.any { domain -> host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true) }
    }

    /** Plattformen mit Ablegern unter vielen Länderendungen (amazon.de, amazon.co.uk …). */
    private val PLATFORM_NAMES = setOf("amazon", "audible", "spotify")

    private val PLATFORM_DOMAINS = listOf(
        "apple.com", "spotify.link", "anchor.fm", "amzn.to", "deezer.com", "deezer.page.link", "youtube.com", "youtu.be",
        "google.com", "goo.gl", "audionow.de", "podimo.com", "podcasts.rtl.de", "plus.rtl.de", "castbox.fm",
    )

    /** Lucene-Phrase der MusicBrainz-Suche. */
    private fun phrase(value: String): String = "\"" + value.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
