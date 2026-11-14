package de.kleinabersmart.earloft.core.model

/**
 * Art einer Folge aus dem Feed (`itunes:episodeType`, Konzept 12.5 „Podcasting 2.0“). Trailer fallen in allen Listen weg,
 * sobald das Abo echte Folgen hat; Bonusfolgen sind normale Folgen mit Kennzeichen.
 */
enum class EpisodeType { FULL, TRAILER, BONUS }

/**
 * Mitwirkende einer Folge (`podcast:person`): Name, Rolle und Gruppe wie im Feed (Podcasting-2.0-Taxonomie, klein
 * geschrieben, `null` = „host“), Bild und Webseite nur als absolute http(s)-Adresse.
 */
data class EpisodePerson(
    val name: String,
    val role: String? = null,
    val group: String? = null,
    val imageUrl: String? = null,
    val url: String? = null,
)

/**
 * Transkript einer Folge (`podcast:transcript`, Konzept 12.5 „Transkripte“), in Absätze gefasst. Absätze aus SRT, WebVTT
 * und JSON haben Beginn und Ende (Position in der Folge), solche aus HTML meist nur den Beginn, aus reinem Text keins.
 */
data class Transcript(val segments: List<TranscriptSegment>) {
    /** Läuft mit der Wiedergabe mit: mindestens ein Absatz hat einen Beginn. */
    val timed: Boolean get() = segments.any { it.startMs != null }
}

data class TranscriptSegment(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val speaker: String? = null,
)
