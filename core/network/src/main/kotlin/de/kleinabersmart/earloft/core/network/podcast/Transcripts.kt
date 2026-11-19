package de.kleinabersmart.earloft.core.network.podcast

import de.kleinabersmart.earloft.core.model.Transcript
import de.kleinabersmart.earloft.core.model.TranscriptSegment
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.math.roundToLong

/** Formats of `podcast:transcript` (concept 12.5 "transcripts"), ordered by usefulness: with times and speakers first. */
enum class TranscriptFormat { JSON, VTT, SRT, HTML, TEXT }

/** Format by MIME type, otherwise by file extension; `null` for unknown ones (e.g. PDF). */
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
 * Of several transcripts of an episode (URL, MIME type), the one with the most useful format ([TranscriptFormat]); with
 * the same format the first one. `null` if none is readable.
 */
fun pickTranscript(candidates: List<Pair<String, String?>>): Pair<String, String?>? =
    candidates
        .mapNotNull { (url, type) -> transcriptFormat(type, url)?.let { Triple(url, type, it) } }
        .minByOrNull { it.third.ordinal }
        ?.let { (url, type) -> url to type }

/**
 * Parses a transcript. If the declared format doesn't match the content (some feeds specify `text/plain` for
 * SRT), the content decides. Single words (JSON) and short subtitle lines are combined into paragraphs.
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
        throw IOException("Unreadable transcript", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("Unreadable transcript", e)
    }

// --- SRT and WebVTT ---

private val CUE_TIMING = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})""")
private val VOICE = Regex("""<v(?:\.[^\s>]*)?\s+([^>]+)>""")
private val TAG = Regex("""<[^>]*>""")

/** Subtitle blocks: a line with "start --> end", below it the text up to the empty line; header, NOTE, STYLE and numbers are dropped. */
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

/** "01:02:03,500", "01:02:03.500" or "02:03.500" in ms. */
private fun cueTime(raw: String): Long? {
    val parts = raw.replace(',', '.').split(':')
    val numbers = parts.map { it.toDoubleOrNull() ?: return null }
    return (numbers.fold(0.0) { total, part -> total * 60 + part } * 1000).roundToLong()
}

// --- HTML (Podcasting 2.0: <cite>Speaker:</cite> <time>0:00</time> <p>Text</p>) ---

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

/** Plain text: paragraphs at empty lines, otherwise at lines. */
private fun plainParagraphs(body: String): List<TranscriptSegment> {
    val blocks = body.split(Regex("""\n\s*\n""")).map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    val paragraphs = if (blocks.size > 1) blocks else body.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return paragraphs.map { TranscriptSegment(it) }
}

// --- Paragraphs ---

private const val MIN_PARAGRAPH = 80
private const val MAX_PARAGRAPH = 400
private val SENTENCE_END = Regex("""[.!?…]["'»«“”)\]]*$""")
private const val DASHES = "-–—"
private val NO_SPACE_BEFORE = Regex("""^[,.;:!?…)\]»“”'’]""")

/**
 * Combines words or subtitle lines into paragraphs: a new one starts when the speaker changes, after the end of a sentence
 * from [MIN_PARAGRAPH] characters on, or at the latest from [MAX_PARAGRAPH] characters on.
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
        // A single punctuation mark ("-", "…") attaches to the previous paragraph instead of starting a new one.
        val wordless = piece.none { it.isLetterOrDigit() }
        val ending = text.trimEnd { it.isWhitespace() || it in DASHES }
        val full = (text.length >= MIN_PARAGRAPH && SENTENCE_END.containsMatchIn(ending)) || text.length >= MAX_PARAGRAPH
        if (open != null && (newSpeaker || (full && !wordless))) {
            flush()
        }
        if (current == null) {
            // Subtitle lines without a voice belong to the previous speaker.
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
