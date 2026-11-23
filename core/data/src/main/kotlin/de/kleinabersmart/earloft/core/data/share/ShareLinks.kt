package de.kleinabersmart.earloft.core.data.share

import de.kleinabersmart.earloft.core.model.hasContributorRole
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What is shared: [subject] as subject or preview title, [text] with title and link (concept 13). */
data class ShareContent(val subject: String, val text: String)

enum class MusicBrainzKind(val path: String) {
    RECORDING("recording"),
    RELEASE("release"),
    ARTIST("artist"),
}

/**
 * Links to public, non-commercial directories (concept 13). The app never fetches them itself; without an ID
 * it becomes a search link that only the recipient opens.
 */
object ShareLinks {
    private const val MUSICBRAINZ = "https://musicbrainz.org"
    private val MBID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val OPEN_LIBRARY = "https://openlibrary.org"
    private val ISBN = Regex("[0-9]{9}[0-9X]|[0-9]{13}")

    /** Page of the [mbid] on MusicBrainz, otherwise the search for [title] (and [artist], where there is one). */
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

    /**
     * Book on Open Library: via a valid [isbn], otherwise the search for [title] and [author]. The search requires all
     * words; subtitles, parenthetical additions ("Unabridged", series) and translators from Audiobookshelf make it come up empty.
     */
    fun openLibrary(isbn: String?, title: String, author: String?): String {
        val number = isbn?.filter { it.isLetterOrDigit() }?.uppercase()
        if (number != null && ISBN.matches(number)) return "$OPEN_LIBRARY/isbn/$number"
        val mainAuthor = author?.let(::mainAuthor)
        return "$OPEN_LIBRARY/search".toHttpUrl().newBuilder()
            .addQueryParameter("title", mainTitle(title))
            .apply { if (mainAuthor != null) addQueryParameter("author", mainAuthor) }
            .build()
            .toString()
    }

    /** Title without subtitle (after ":") and without parenthetical additions at the end. */
    internal fun mainTitle(title: String): String {
        var main = title.substringBefore(": ").trim()
        while (true) {
            val stripped = main.replace(TRAILING_NOTE, "").trim()
            if (stripped == main || stripped.isEmpty()) break
            main = stripped
        }
        return main.ifEmpty { title.trim() }
    }

    /** First name from the Audiobookshelf list that doesn't carry a role such as translator or narrator. */
    internal fun mainAuthor(authors: String): String? =
        authors.split(',', '&', ';')
            .map { it.trim() }
            .firstOrNull { name -> name.isNotEmpty() && !hasContributorRole(name) }

    private val TRAILING_NOTE = Regex("""\s*[(\[][^()\[\]]*[)\]]$""")

    /**
     * A web page to share: absolute http(s) address that neither belongs to a platform ([isPlatform]) nor is private
     * like a feed ([isPrivateSource]: credentials, home network, own server; review J23).
     */
    fun publicPage(url: String?, ownHosts: Set<String> = emptySet()): String? {
        val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
        return url.trim().takeUnless { isPlatform(parsed.host) || isPrivateSource(it, ownHosts) }
    }

    /** Feed or audio file: the open source itself, wherever it is; only absolute http(s) addresses. */
    fun sourceUrl(url: String?): String? = url?.trim()?.takeIf { it.toHttpUrlOrNull() != null }

    /**
     * Feed or audio file that must not be passed on: with credentials (user in the address, parameters such as
     * `auth`, `token`, `key` for paid feeds), on the home network or on an own server ([ownHosts]). A token in the
     * path (some hosts) can't be detected this way.
     */
    fun isPrivateSource(url: String, ownHosts: Set<String> = emptySet()): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return true
        if (parsed.queryParameterNames.any(::isSecretParameter)) return true
        val host = parsed.host.lowercase()
        return host in ownHosts || isLocalHost(host)
    }

    private fun isSecretParameter(name: String): Boolean {
        val key = name.lowercase().replace("-", "_")
        return key in SECRET_PARAMETERS || SECRET_SUFFIXES.any { key.endsWith(it) }
    }

    private val SECRET_PARAMETERS = setOf("auth", "authorization", "key", "secret", "sig", "signature", "password", "pass", "pwd", "session", "sid")
    private val SECRET_SUFFIXES = listOf("token", "_key", "apikey", "secret")

    /** Home network: loopback, private and link-local addresses, names without a dot and local suffixes. */
    private fun isLocalHost(host: String): Boolean {
        if ('.' !in host && ':' !in host) return true
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        if (':' in host) return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4) return false
        val (a, b) = octets
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }

    private val LOCAL_SUFFIXES = listOf("localhost", ".local", ".lan", ".home", ".internal", ".home.arpa", ".fritz.box")

    /**
     * Retailers, streaming and podcast platforms that are never linked to (concept 13). Hosting providers stay: their pages
     * belong to the podcast itself.
     */
    fun isPlatform(host: String): Boolean {
        val labels = host.lowercase().removeSuffix(".").split('.')
        if (labels.dropLast(1).any { it in PLATFORM_NAMES }) return true
        return PLATFORM_DOMAINS.any { domain -> host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true) }
    }

    /** Platforms with offshoots under many country domains (amazon.de, amazon.co.uk …). */
    private val PLATFORM_NAMES = setOf("amazon", "audible", "spotify")

    private val PLATFORM_DOMAINS = listOf(
        "apple.com", "spotify.link", "anchor.fm", "amzn.to", "deezer.com", "deezer.page.link", "youtube.com", "youtu.be",
        "google.com", "goo.gl", "audionow.de", "podimo.com", "podcasts.rtl.de", "plus.rtl.de", "castbox.fm",
    )

    /** Lucene phrase of the MusicBrainz search. */
    private fun phrase(value: String): String = "\"" + value.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
