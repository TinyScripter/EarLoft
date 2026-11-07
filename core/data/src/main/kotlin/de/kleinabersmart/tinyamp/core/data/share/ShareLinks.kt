package de.kleinabersmart.tinyamp.core.data.share

import okhttp3.HttpUrl.Companion.toHttpUrl

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

    /** Lucene-Phrase der MusicBrainz-Suche. */
    private fun phrase(value: String): String = "\"" + value.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
