package de.kleinabersmart.earloft.core.network.podcast.directory

import de.kleinabersmart.earloft.core.network.podcast.normalizeFeedUrl

/** Public podcast directories searched by the "Add podcast" page (concept 12.10); in display order. */
enum class PodcastDirectory {
    APPLE, PODCAST_INDEX, FYYD, ARD_SOUNDS;

    /** Has charts or trends for "Discover" (Apple: top podcasts per country, Podcast Index: trending). */
    val discoverable: Boolean get() = this == APPLE || this == PODCAST_INDEX
}

/**
 * Categories for discovering, in display order: the main genres of Apple Podcasts ([appleGenre]) and the matching
 * categories of Podcast Index ([podcastIndex], names separated by commas).
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

/** A hit of the directory search. */
data class DirectoryPodcast(
    val title: String,
    val author: String,
    /** Subscription URL: usually an http(s) URL, for shows from ARD Sounds without an RSS feed `ardsounds:<id>` (concept 12.10). */
    val feedUrl: String,
    val imageUrl: String?,
    /** All directories that know this feed. */
    val directories: Set<PodcastDirectory>,
    /**
     * Subscription URL `ardsounds:<id>` if ARD Sounds knows the show and [feedUrl] is its RSS feed: then the user
     * chooses whether the subscription runs via the feed or via ARD Sounds (concept 12.10).
     */
    val ardSoundsUrl: String? = null,
)

/**
 * Comparison key of a feed URL: without scheme, `www.` and trailing slash, host lower-cased. Directories
 * often write the same feed differently, and the app stores `http://` feeds as `https://` where possible.
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
 * Interleaves the hits of several directories round-robin (the first of each directory, then the second …), so that the
 * best hits of all directories are at the top. The same feed appears only once, with all directories that
 * know it; missing details (author, image) are taken from the later hits.
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
