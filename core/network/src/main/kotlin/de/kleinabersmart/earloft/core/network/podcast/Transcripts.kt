package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.model.Transcript
import de.kleinabersmart.earloft.core.model.TranscriptSegment
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.math.roundToLong

/** Formate von `podcast:transcript` (Konzept 12.5 „Transkripte“), nach Nutzen geordnet: mit Zeiten und Sprechern zuerst. */
enum class TranscriptFormat { JSON, VTT, SRT, HTML, TEXT }

/** Format nach MIME-Typ, sonst nach Dateiendung; `null` bei Unbekanntem (etwa PDF). */
fun transcriptFormat(type: String?, url: String): TranscriptFormat? {
    val mime = type?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        mime == "application/json" || mime.endsWith("+json") -> TranscriptFormat.JSON
        mime == "text/vtt" -> TranscriptFormat.VTT
        mime == "application/srt" || mime == "application/x-subrip" || mime == "text/srt" -> TranscriptFormat.SRT
        mime == "text/html" -> TranscriptFormat.HTML
        mime == "text/plain" -> TranscriptFormat.TEXT
        else -> when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "json" -> TranscriptFormat.JSON
            "vtt" -> TranscriptFormat.VTT
            "srt" -> TranscriptFormat.SRT
            "html", "htm" -> TranscriptFormat.HTML
            "txt" -> TranscriptFormat.TEXT
            else -> null
        }
    }
}

/**
 * Aus mehreren Transkripten einer Folge (Adresse, MIME-Typ) das mit dem nützlichsten Format ([TranscriptFormat]); bei
 * gleichem Format das erste. `null`, wenn keins lesbar ist.
 */
fun pickTranscript(candidates: List<Pair<String, String?>>): Pair<String, String?>? =
    candidates
        .mapNotNull { (url, type) -> transcriptFormat(type, url)?.let { Triple(url, type, it) } }
        .minByOrNull { it.third.ordinal }
        ?.let { (url, type) -> url to type }

/**
 * Liest ein Transkript. Stimmt das angegebene Format nicht mit dem Inhalt überein (manche Feeds nennen `text/plain` für
 * SRT), entscheidet der Inhalt. Einzelne Wörter (JSON) und kurze Untertitelzeilen werden zu Absätzen zusammengefasst.
 */
fun parseTranscript(text: String, declared: TranscriptFormat?): Transcript {
    val body = text.trimStart(BOM).replace("\r\n", "\n").replace('\r', '\n')
    val format = sniffFormat(body) ?: declared ?: TranscriptFormat.TEXT
    val segments = when (format) {
        TranscriptFormat.JSON -> mergeSegments(parseJson(body))
        TranscriptFormat.VTT, TranscriptFormat.SRT -> mergeSegments(parseCues(body))
        TranscriptFormat.HTML -> parseHtml(body)
        TranscriptFormat.TEXT -> plainParagraphs(body)
    }
    return Transcript(segments.filter { it.text.isNotBlank() })
}

private fun sniffFormat(body: String): TranscriptFormat? {
    val start = body.trimStart()
    return when {
        start.startsWith("WEBVTT") -> TranscriptFormat.VTT
        start.startsWith("{") -> TranscriptFormat.JSON
        SRT_START.containsMatchIn(start.take(200)) -> TranscriptFormat.SRT
        start.startsWith("<") -> TranscriptFormat.HTML
        else -> null
    }
}

private val BOM = Char(0xFEFF)

private val SRT_START = Regex("""^\d+\s*\n\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""")

// --- JSON (Podcasting 2.0) ---

@Serializable
private data class TranscriptFile(val segments: List<SegmentDto> = emptyList())

@Serializable
private data class SegmentDto(
    val startTime: Double? = null,
    val endTime: Double? = null,
    val speaker: String? = null,
    val body: String = "",
)

private val TRANSCRIPT_JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

private fun parseJson(body: String): List<TranscriptSegment> =
    try {
        TRANSCRIPT_JSON.decodeFromString<TranscriptFile>(body).segments.map {
            TranscriptSegment(
                text = it.body,
                startMs = it.startTime?.takeIf { s -> s >= 0 }?.let { s -> (s * 1000).roundToLong() },
                endMs = it.endTime?.takeIf { s -> s >= 0 }?.let { s -> (s * 1000).roundToLong() },
                speaker = it.speaker?.trim()?.takeIf { s -> s.isNotEmpty() },
            )
        }
    } catch (e: SerializationException) {
        throw IOException("Unlesbares Transkript", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("Unlesbares Transkript", e)
    }

// --- SRT und WebVTT ---

private val CUE_TIMING = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})""")
private val VOICE = Regex("""<v(?:\.[^\s>]*)?\s+([^>]+)>""")
private val TAG = Regex("""<[^>]*>""")

/** Untertitel-Blöcke: Zeile mit „start --> ende“, darunter der Text bis zur Leerzeile; Kopf, NOTE, STYLE und Nummern fallen weg. */
private fun parseCues(body: String): List<TranscriptSegment> {
    val result = mutableListOf<TranscriptSegment>()
    for (block in body.split(Regex("""\n\s*\n"""))) {
        val lines = block.lines()
        val timingIndex = lines.indexOfFirst { CUE_TIMING.containsMatchIn(it) }
        if (timingIndex < 0) continue
        val timing = CUE_TIMING.find(lines[timingIndex])!!
        val raw = lines.drop(timingIndex + 1).joinToString(" ") { it.trim() }.trim()
        if (raw.isEmpty()) continue
        val speaker = VOICE.find(raw)?.groupValues?.get(1)?.trim()
        result += TranscriptSegment(
            text = decodeHtmlEntities(raw.replace(TAG, "")).replace(Regex("""\s+"""), " ").trim(),
            startMs = cueTime(timing.groupValues[1]),
            endMs = cueTime(timing.groupValues[2]),
            speaker = speaker?.takeIf { it.isNotEmpty() },
        )
    }
    return result
}

/** „01:02:03,500“, „01:02:03.500“ oder „02:03.500“ in ms. */
private fun cueTime(raw: String): Long? {
    val parts = raw.replace(',', '.').split(':')
    val numbers = parts.map { it.toDoubleOrNull() ?: return null }
    return (numbers.fold(0.0) { total, part -> total * 60 + part } * 1000).roundToLong()
}

// --- HTML (Podcasting 2.0: <cite>Sprecher:</cite> <time>0:00</time> <p>Text</p>) ---

private val HTML_PART = Regex("""<(cite|time|p)\b[^>]*>(.*?)</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val BREAK = Regex("""<br\s*/?>|</(?:p|div|li|h\d)>""", RegexOption.IGNORE_CASE)

private fun parseHtml(body: String): List<TranscriptSegment> {
    val parts = HTML_PART.findAll(body).toList()
    if (parts.none { it.groupValues[1].equals("p", ignoreCase = true) }) {
        return plainParagraphs(decodeHtmlEntities(body.replace(BREAK, "\n\n").replace(TAG, "")))
    }
    var speaker: String? = null
    var start: Long? = null
    val result = mutableListOf<TranscriptSegment>()
    for (part in parts) {
        val content = decodeHtmlEntities(part.groupValues[2].replace(TAG, "")).replace(Regex("""\s+"""), " ").trim()
        when (part.groupValues[1].lowercase()) {
            "cite" -> speaker = content.removeSuffix(":").trim().takeIf { it.isNotEmpty() }
            "time" -> start = cueTime(content)
            else -> {
                result += TranscriptSegment(content, startMs = start, speaker = speaker)
                start = null
            }
        }
    }
    return result
}

/** Reiner Text: Absätze an Leerzeilen, sonst an Zeilen. */
private fun plainParagraphs(body: String): List<TranscriptSegment> {
    val blocks = body.split(Regex("""\n\s*\n""")).map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    val paragraphs = if (blocks.size > 1) blocks else body.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return paragraphs.map { TranscriptSegment(it) }
}

// --- Absätze ---

private const val MIN_PARAGRAPH = 80
private const val MAX_PARAGRAPH = 400
private val SENTENCE_END = Regex("""[.!?…]["'»«“”)\]]*$""")
private const val DASHES = "-–—"
private val NO_SPACE_BEFORE = Regex("""^[,.;:!?…)\]»“”'’]""")

/**
 * Fasst Wörter bzw. Untertitelzeilen zu Absätzen: Ein neuer beginnt, wenn der Sprecher wechselt, nach einem Satzende
 * ab [MIN_PARAGRAPH] Zeichen oder spätestens ab [MAX_PARAGRAPH] Zeichen.
 */
internal fun mergeSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> {
    val result = mutableListOf<TranscriptSegment>()
    var current: TranscriptSegment? = null
    val text = StringBuilder()
    fun flush() {
        current?.let { result += it.copy(text = text.toString().trim()) }
        current = null
        text.clear()
    }
    for (segment in segments) {
        val piece = segment.text.trim()
        if (piece.isEmpty()) continue
        val open = current
        val newSpeaker = segment.speaker != null && open != null && segment.speaker != open.speaker
        // Ein einzelnes Satzzeichen („-“, „…“) hängt am Absatz davor, statt einen neuen zu beginnen.
        val wordless = piece.none { it.isLetterOrDigit() }
        val ending = text.trimEnd { it.isWhitespace() || it in DASHES }
        val full = (text.length >= MIN_PARAGRAPH && SENTENCE_END.containsMatchIn(ending)) || text.length >= MAX_PARAGRAPH
        if (open != null && (newSpeaker || (full && !wordless))) {
            flush()
        }
        if (current == null) {
            // Untertitelzeilen ohne Stimme gehören zum Sprecher davor.
            current = segment.copy(speaker = segment.speaker ?: open?.speaker)
        } else {
            current = current!!.copy(endMs = segment.endMs ?: current!!.endMs)
            if (!NO_SPACE_BEFORE.containsMatchIn(piece)) text.append(' ')
        }
        text.append(piece)
    }
    flush()
    return result
}
