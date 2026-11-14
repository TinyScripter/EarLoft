package de.kleinabersmart.earloft.core.network.podcast.directory

import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl

/** Öffentliche Podcast-Verzeichnisse, in denen die Seite „Podcast hinzufügen“ sucht (Konzept 12.10); in Anzeigereihenfolge. */
enum class PodcastDirectory {
    APPLE, PODCAST_INDEX, FYYD, ARD_SOUNDS;

    /** Hat Charts bzw. Trends für „Entdecken“ (Apple: Top-Podcasts je Land, Podcast Index: Trending). */
    val discoverable: Boolean get() = this == APPLE || this == PODCAST_INDEX
}

/**
 * Kategorien fürs Entdecken, in Anzeigereihenfolge: die Hauptgenres von Apple Podcasts ([appleGenre]) und die passenden
 * Kategorien von Podcast Index ([podcastIndex], Namen durch Komma getrennt).
 */
enum class PodcastCategory(val appleGenre: Int, val podcastIndex: String) {
    NEWS(1489, "News"),
    SOCIETY(1324, "Society,Culture"),
    COMEDY(1303, "Comedy"),
    HISTORY(1487, "History"),
    TRUE_CRIME(1488, "True Crime"),
    SCIENCE(1533, "Science"),
    TECHNOLOGY(1318, "Technology"),
    BUSINESS(1321, "Business"),
    GOVERNMENT(1511, "Government"),
    EDUCATION(1304, "Education"),
    HEALTH(1512, "Health,Fitness"),
    SPORTS(1545, "Sports"),
    ARTS(1301, "Arts"),
    FICTION(1483, "Fiction"),
    MUSIC(1310, "Music"),
    TV_FILM(1309, "TV,Film"),
    LEISURE(1502, "Leisure"),
    KIDS(1305, "Kids,Family"),
    RELIGION(1314, "Religion,Spirituality"),
}

/** Ein Treffer der Verzeichnissuche. */
data class DirectoryPodcast(
    val title: String,
    val author: String,
    /** Abo-Adresse: meist eine http(s)-Adresse, bei Sendungen aus ARD Sounds ohne RSS-Feed `ardsounds:<Kennung>` (Konzept 12.10). */
    val feedUrl: String,
    val imageUrl: String?,
    /** Alle Verzeichnisse, die diesen Feed kennen. */
    val directories: Set<PodcastDirectory>,
    /**
     * Abo-Adresse `ardsounds:<Kennung>`, wenn ARD Sounds die Sendung kennt und [feedUrl] ihr RSS-Feed ist: Dann wählt der
     * Nutzer, ob das Abo über den Feed oder über ARD Sounds läuft (Konzept 12.10).
     */
    val ardSoundsUrl: String? = null,
)

/**
 * Vergleichsschlüssel einer Feed-Adresse: ohne Schema, `www.` und Schrägstrich am Ende, Host klein. Verzeichnisse
 * schreiben denselben Feed oft verschieden, und die App speichert `http://`-Feeds nach Möglichkeit als `https://`.
 */
fun feedKey(url: String): String {
    val http = normalizeFeedUrl(url) ?: return url.trim().lowercase()
    val host = http.host.removePrefix("www.")
    val port = if (http.port == defaultPort(http.scheme)) "" else ":${http.port}"
    val query = http.encodedQuery?.let { "?$it" }.orEmpty()
    return host + port + http.encodedPath.trimEnd('/') + query
}

private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

/**
 * Mischt die Treffer mehrerer Verzeichnisse reihum (der erste jedes Verzeichnisses, dann der zweite …), damit die
 * besten Treffer aller Verzeichnisse oben stehen. Derselbe Feed erscheint nur einmal, mit allen Verzeichnissen, die ihn
 * kennen; fehlende Angaben (Autor, Bild) übernimmt er aus den späteren Treffern.
 */
fun mergeDirectoryResults(results: List<List<DirectoryPodcast>>): List<DirectoryPodcast> {
    val merged = LinkedHashMap<String, DirectoryPodcast>()
    val rounds = results.maxOfOrNull { it.size } ?: 0
    for (index in 0 until rounds) {
        for (list in results) {
            val hit = list.getOrNull(index) ?: continue
            val key = feedKey(hit.feedUrl)
            val known = merged[key]
            merged[key] = if (known == null) {
                hit
            } else {
                known.copy(
                    author = known.author.ifBlank { hit.author },
                    imageUrl = known.imageUrl ?: hit.imageUrl,
                    directories = known.directories + hit.directories,
                    ardSoundsUrl = known.ardSoundsUrl ?: hit.ardSoundsUrl,
                )
            }
        }
    }
    return merged.values.toList()
}
