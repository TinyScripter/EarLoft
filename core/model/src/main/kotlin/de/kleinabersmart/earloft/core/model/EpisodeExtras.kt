package de.kleinabersmart.earloft.core.model

/**
 * Type of an episode from the feed (`itunes:episodeType`, concept 12.5 "Podcasting 2.0"). Trailers are dropped from all lists
 * as soon as the subscription has real episodes; bonus episodes are regular episodes with a marker.
 */
enum class EpisodeType { FULL, TRAILER, BONUS }

/**
 * Contributors of an episode (`podcast:person`): name, role and group as in the feed (Podcasting 2.0 taxonomy, lower
 * case, `null` = "host"), image and web page only as an absolute http(s) URL.
 */
data class EpisodePerson(
    val name: String,
    val role: String? = null,
    val group: String? = null,
    val imageUrl: String? = null,
    val url: String? = null,
)

/**
 * Transcript of an episode (`podcast:transcript`, concept 12.5 "transcripts"), grouped into paragraphs. Paragraphs from SRT, WebVTT
 * and JSON have a start and end (position in the episode), those from HTML usually only the start, from plain text neither.
 */
data class Transcript(val segments: List<TranscriptSegment>) {
    /** Follows along with playback: at least one paragraph has a start. */
    val timed: Boolean get() = segments.any { it.startMs != null }
}

data class TranscriptSegment(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val speaker: String? = null,
)
