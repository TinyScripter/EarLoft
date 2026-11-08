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
    private const val OPEN_LIBRARY = "https://openlibrary.org"
    private val ISBN = Regex("[0-9]{9}[0-9X]|[0-9]{13}")

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

    /**
     * Buch bei Open Library: über eine gültige [isbn], sonst die Suche nach [title] und [author]. Die Suche verlangt alle
     * Wörter; Untertitel, Klammerzusätze („Unabridged“, Reihe) und Übersetzer aus Audiobookshelf lassen sie leer ausgehen.
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

    /** Titel ohne Untertitel (nach „:“) und ohne Zusätze in Klammern am Ende. */
    internal fun mainTitle(title: String): String {
        var main = title.substringBefore(": ").trim()
        while (true) {
            val stripped = main.replace(TRAILING_NOTE, "").trim()
            if (stripped == main || stripped.isEmpty()) break
            main = stripped
        }
        return main.ifEmpty { title.trim() }
    }

    /** Erster Name aus der Liste von Audiobookshelf, der keine Rolle wie Übersetzer oder Sprecher trägt. */
    internal fun mainAuthor(authors: String): String? =
        authors.split(',', '&', ';')
            .map { it.trim() }
            .firstOrNull { name -> name.isNotEmpty() && ROLES.none { name.contains(it, ignoreCase = true) } }

    private val TRAILING_NOTE = Regex("""\s*[(\[][^()\[\]]*[)\]]$""")
    private val ROLES = listOf("translator", "übersetz", "narrator", "sprecher", "editor", "herausgeber", "illustrator", "foreword", "vorwort")

    /** Eine Webseite zum Teilen: absolute http(s)-Adresse, die nicht zu einer Plattform gehört ([isPlatform]). */
    fun publicPage(url: String?): String? {
        val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
        return url.trim().takeUnless { isPlatform(parsed.host) }
    }

    /** Feed oder Audiodatei: die offene Quelle selbst, egal wo sie liegt; nur absolute http(s)-Adressen. */
    fun sourceUrl(url: String?): String? = url?.trim()?.takeIf { it.toHttpUrlOrNull() != null }

    /**
     * Feed oder Audiodatei, die nicht weitergegeben werden darf: mit Zugangsdaten (Benutzer in der Adresse, Parameter wie
     * `auth`, `token`, `key` bei Bezahl-Feeds), im Heimnetz oder auf einem eigenen Server ([ownHosts]). Ein Token im
     * Pfad (manche Hoster) ist so nicht zu erkennen.
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

    /** Heimnetz: Loopback, private und Link-Local-Adressen, Namen ohne Punkt und lokale Endungen. */
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
