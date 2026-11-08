package de.kleinabersmart.earloft.core.network.podcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream

/** Kein lesbares OPML. */
class OpmlException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Ein Abo in einer OPML-Datei. */
data class OpmlFeed(val title: String, val url: String, val htmlUrl: String? = null)

/** Liest und schreibt OPML 2.0, wie es Podcast-Apps zum Austausch von Abos nutzen. */
object Opml {
    /**
     * Alle `outline`-Einträge mit `xmlUrl`, auch in Gruppen verschachtelte, in Dateireihenfolge. Einträge ohne gültige
     * Feed-Adresse entfallen; der Titel fällt auf `text`, dann auf den Host zurück. Dubletten bleiben erhalten.
     */
    fun parse(input: InputStream): List<OpmlFeed> {
        val feeds = mutableListOf<OpmlFeed>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(input.rejectingEntityDeclarations() ?: throw OpmlException("OPML mit eigenen XML-Entities"), null)
            var sawOpml = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.lowercase()) {
                        "opml" -> sawOpml = true
                        "outline" -> {
                            val url = parser.attr("xmlUrl")?.trim().orEmpty()
                            val http = normalizeFeedUrl(url)
                            if (http != null) {
                                val title = (parser.attr("title") ?: parser.attr("text"))?.trim().orEmpty()
                                feeds += OpmlFeed(title.ifEmpty { http.host }, http.toString(), parser.attr("htmlUrl")?.trim()?.ifEmpty { null })
                            }
                        }
                    }
                }
                event = parser.next()
            }
            if (!sawOpml) throw OpmlException("Kein OPML")
        } catch (e: XmlPullParserException) {
            throw OpmlException("Kein gültiges XML", e)
        }
        return feeds
    }

    fun write(feeds: List<OpmlFeed>, title: String, createdAt: String): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<opml version=\"2.0\">")
        appendLine("  <head>")
        appendLine("    <title>${title.escapeXml()}</title>")
        appendLine("    <dateCreated>${createdAt.escapeXml()}</dateCreated>")
        appendLine("  </head>")
        appendLine("  <body>")
        feeds.forEach { feed ->
            val name = feed.title.escapeXml()
            append("    <outline text=\"$name\" title=\"$name\" type=\"rss\" xmlUrl=\"${feed.url.escapeXml()}\"")
            feed.htmlUrl?.let { append(" htmlUrl=\"${it.escapeXml()}\"") }
            appendLine(" />")
        }
        appendLine("  </body>")
        appendLine("</opml>")
    }

    private fun XmlPullParser.attr(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeName(it).equals(name, ignoreCase = true) }?.let { getAttributeValue(it) }

    private fun String.escapeXml() = buildString {
        for (c in this@escapeXml) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
